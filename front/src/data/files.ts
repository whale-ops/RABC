/**
 * 通过 IndexedDB 保存和读取原型文件内容，并提供文件大小格式化。
 * 关联模块：Files.vue 负责界面与操作，store.ts 保存文件清单；此处不是后台文件上传或对象存储服务。
 */
function openStore(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("star-admin-files", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("files");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
export async function putFile(id: string, file: File): Promise<void> {
  const db = await openStore();
  return new Promise((resolve, reject) => {
    const tx = db.transaction("files", "readwrite");
    tx.objectStore("files").put(file, id);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => {
      db.close();
      reject(tx.error);
    };
  });
}
export async function getFile(id: string): Promise<File | undefined> {
  const db = await openStore();
  return new Promise((resolve, reject) => {
    const request = db.transaction("files").objectStore("files").get(id);
    request.onsuccess = () => {
      db.close();
      resolve(request.result);
    };
    request.onerror = () => {
      db.close();
      reject(request.error);
    };
  });
}
export async function deleteFile(id: string): Promise<void> {
  const db = await openStore();
  return new Promise((resolve, reject) => {
    const tx = db.transaction("files", "readwrite");
    tx.objectStore("files").delete(id);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => {
      db.close();
      reject(tx.error);
    };
  });
}
export function formatSize(size: number) {
  if (size === 0) return "0 B";
  return size >= 1024 * 1024
    ? (size / 1024 / 1024).toFixed(2) + " MB"
    : Math.max(1, Math.round(size / 1024)) + " KB";
}
