/** 图片与附件的展示分类；范围与后台 StorageCategory 一致，后端会再次校验上传。 */
export type StorageCategory = "images" | "videos" | "attachments";
export const imageAccept = ".png,.jpg,.jpeg,.gif,.webp,.bmp,.avif";
export const isStorageImage = (name: string) => /\.(png|jpe?g|gif|webp|bmp|avif)$/i.test(name);
// 与后台分类一致；是否能播放还取决于视频编码，页面提供播放失败提示。
export const videoAccept = ".mp4,.webm,.ogv,.mov,.m4v";
export const isStorageVideo = (name: string) => /\.(mp4|webm|ogv|mov|m4v)$/i.test(name);
