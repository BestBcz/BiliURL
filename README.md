### WIP
## BiliVideoParser

[![Github](https://img.shields.io/badge/-Github-000?style=flat&logo=Github&logoColor=white)](https://github.com/BestBcz)
[![MiraiForum](https://img.shields.io/badge/Forum-Mirai?style=flat-square&label=Mirai
)](https://mirai.mamoe.net/topic/2795/biliurl%E4%B8%80%E4%B8%AA%E7%AE%80%E5%8D%95%E7%9A%84%E8%A7%A3%E6%9E%90qq%E5%88%86%E4%BA%AB%E5%93%94%E5%93%A9%E5%93%94%E5%93%A9%E5%B0%8F%E7%A8%8B%E5%BA%8F%E8%A7%86%E9%A2%91%E5%9C%B0%E5%9D%80%E7%9A%84%E5%B0%8F%E6%8F%92%E4%BB%B6)

-------------------------
#### 🌱 解析QQ中分享的哔哩哔哩小程序视频并下载发送的mirai插件

---------------------------
### 🛠️安装&依赖前置
1. 从Release中下载最新版本
2. 将Jar文件放入 _%mirai文件根目录%/Plugins/_ 中
3. 重新启动你的mirai-console
4. Enjoy~
#### 🛠️依赖前置（请安装到你搭建机器人的服务器上）
-  [FFmpeg](https://ffmpeg.org/download.html)
- [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases)
- [Mirai-console 2.16版本或以上](https://github.com/mamoe/mirai/releases)
> [!IMPORTANT]
> _以上为下载视频功能必需依赖，其他功能无特定依赖前置_


----------------------------------------

### 🚀代办清单 （可以订阅Release来获取更新)

- [x] ~~竖屏视频分辨率缺失~~
- [x] ~~视频缩略图获取失败 只能使用默认黑色缩略图~~
- [ ] MacOS登录协议的支持
- [ ] 设置不同群开启
- [ ] 指令（控制台指令和聊天框指令）

-------------------------------------------------

### 🧐已拥有的功能

- [x] 视频解析下载   以短视频形式发送到群聊
- [x] 封面下载并单独发送
- [x] 视频链接解析
- [x] 视频详细信息解析
- [x] 自动删除下载视频
- [x] Config文件
- [x] 长链接bilibili.com/video/BVxxx 和 短链接b23.tv/xxxx 的选择

-----------------------------------------
### 📷插件截图
<details>

<summary>相关截图</summary>

![e1ffac4d-3043-4d6b-a814-d6be1497e997.png](https://mirai.mamoe.net/assets/uploads/files/1742203041642-356a4b0d-57b0-4a12-acac-8435e5e4aafa.png)
![QQ_1742202849146.png](https://mirai.mamoe.net/assets/uploads/files/1742203056542-e1ffac4d-3043-4d6b-a814-d6be1497e997.png)
![QQ_1742202875264.png](https://mirai.mamoe.net/assets/uploads/files/1742203072380-qq_1742202849146.png)
![356a4b0d-57b0-4a12-acac-8435e5e4aafa.png](https://mirai.mamoe.net/assets/uploads/files/1742203161015-qq_1742202875264.png)

</details>

-------------------------------------

#### 💡 Config已实现自动更新，旧版Config.yml已失效，新版BiliVideoParserConfig.yml会自动生成在同一文件夹
| Config               | 介绍                                            | Default    | 可改参数     |
|----------------------|-----------------------------------------------|------------|----------|
| configVersion        | 配置版本号，用于自动检测和更新旧版配置，请勿自行修改                    | **IGNORE** |  
| enableParsing        | 是否启用解析功能                                      | true       | false    |         
| logMessages          | 是否记录群消息日志                                     | true       | false    |        
| useShortLink         | 是否使用短链接（b23.tv）；若为 false 则使用长链接（bilibili.com） | true       | false    |
| enableDetailedInfo   | 是否显示详细视频信息（包括up主、播放量、评论数、简介、点赞、收藏、投币、转发）      | true       | false    |
| enableDownload       | 是否启用视频下载功能                                    | true       | false    |


-------------------------------------------
### 编译  - 非开发者请无视
- 如果需要使用pluginbuild 编译请使用
- ```javascript
  ./gradlew clean buildPlugin -x miraiPrepareMetadata
  ```
- 来防止miraiPrepareMetadata造成的报错(理论上普通build也可行)
