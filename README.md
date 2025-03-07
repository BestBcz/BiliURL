一个简易的解析QQ中分享的哔哩哔哩小程序地址的mirai插件

如果需要使用pluginbuild 请使用./gradlew clean buildPlugin -x miraiPrepareMetadata 来防止miraiPrepareMetadata造成的报错
理论上普通build也可行


配置文件
enableParsing: true/false 是否开启解析

logMessages: true/false 是否记录日志

useShortLink: true/false 短link或长link


目前config并不会自动更新，请手动删除旧版config，等待自动生成。自动更新config会在未来实现
