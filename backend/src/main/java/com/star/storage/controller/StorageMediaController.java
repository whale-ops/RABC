package com.star.storage.controller;

import com.star.storage.service.StorageMediaService;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.*;

// 包职责：按短期只读票据流式响应本地及 WebDAV 视频的 GET/HEAD 和单段 Range，不全量缓存。
// 模块联系：票据签发复用文件下载权限，删除文件记录后票据失效；其他文件接口仍要求登录。
@RestController
public class StorageMediaController {
  private final StorageMediaService media;

  public StorageMediaController(StorageMediaService media) {
    this.media = media;
  }

  @RequestMapping(
      value = "/api/v1/storage/media/{ticket}",
      method = {RequestMethod.GET, RequestMethod.HEAD})
  public void read(
      @PathVariable String ticket, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var item = media.open(ticket);
    try (var input = item.stream()) {
      long start = 0, end = item.size() - 1;
      response.setHeader("Accept-Ranges", "bytes");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setContentType(item.contentType());
      String range = request.getHeader("Range");
      if (range != null) {
        try {
          var m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
          if (!m.matches() || m.group(1).isEmpty() && m.group(2).isEmpty())
            throw new IllegalArgumentException();
          if (m.group(1).isEmpty()) {
            long suffix = Long.parseLong(m.group(2));
            if (suffix <= 0) throw new IllegalArgumentException();
            start = Math.max(0, item.size() - suffix);
          } else {
            start = Long.parseLong(m.group(1));
            if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2)));
          }
          if (start > end || start >= item.size()) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
          response.setStatus(416);
          response.setHeader("Content-Range", "bytes */" + item.size());
          return;
        }
        response.setStatus(206);
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + item.size());
      }
      long remaining = end - start + 1;
      response.setContentLengthLong(remaining);
      if ("HEAD".equals(request.getMethod())) return;
      input.skipNBytes(start);
      byte[] buffer = new byte[65536];
      var out = response.getOutputStream();
      while (remaining > 0) {
        int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (count < 0) break;
        out.write(buffer, 0, count);
        remaining -= count;
      }
    }
  }
}
