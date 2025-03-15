### Hi there 👋
### BiliVideoParser

#### 正在开发中的功能

- 发送视频详细信息（已完成|未测试）
- 下载视频并发送（正在开发中|未测试）

[![Github](https://img.shields.io/badge/-Github-000?style=flat&logo=Github&logoColor=white)](https://github.com/BestBcz)
[![MiraiForum](https://img.shields.io/badge/Forum-Mirai?style=flat-square&label=Mirai
)](https://mirai.mamoe.net/topic/2795/biliurl%E4%B8%80%E4%B8%AA%E7%AE%80%E5%8D%95%E7%9A%84%E8%A7%A3%E6%9E%90qq%E5%88%86%E4%BA%AB%E5%93%94%E5%93%A9%E5%93%94%E5%93%A9%E5%B0%8F%E7%A8%8B%E5%BA%8F%E8%A7%86%E9%A2%91%E5%9C%B0%E5%9D%80%E7%9A%84%E5%B0%8F%E6%8F%92%E4%BB%B6)

-------------------------
#### 🌱 一个简易的解析QQ中分享的哔哩哔哩小程序地址的mirai插件

-------------------------------------------------
- 如果需要使用pluginbuild 编译请使用
- ```javascript
  ./gradlew clean buildPlugin -x miraiPrepareMetadata
  ```
- 来防止miraiPrepareMetadata造成的报错(理论上普通build也可行)
-------------------------------------

#### 💡 Config已实现自动更新，旧版Config.yml已失效，新版BiliVideoParserConfig.yml会自动生成在同一文件夹
| Config        | 介绍                                              | Default    | 可改参数     |
|---------------|-------------------------------------------------|------------|----------|
| configVersion | 配置版本号，用于自动检测和更新旧版配置，请勿自行修改                      | **IGNORE** |  
| enableParsing | 是否启用解析功能                                        | true       | false    |         
| logMessages   | 是否记录群消息日志                                       | true       | false    |        
| useShortLink  | 是否使用短链接（b23.tv）；若为 false 则使用长链接（bilibili.com）   | true       | false    |


