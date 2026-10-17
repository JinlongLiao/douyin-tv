/** 只在内置扩展的隔离环境建立通道，不向网站暴露原生对象或任意代码执行接口。 */
var tvNativePort = browser.runtime.connectNative('douyin_tv');
