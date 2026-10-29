/** 历史文件的存储协议标签；未知类型保留服务端标识，不误显示为 MinIO。 */
export function storageProviderLabel(provider: string) {
  return (
    (
      {
        aliyun: "阿里云 OSS",
        minio: "MinIO",
        s3: "S3 兼容存储",
        local: "服务器本地目录",
        webdav: "WebDAV 文件服务器",
      } as Record<string, string>
    )[provider] || provider
  );
}
