# OpenClaw 接入第一个通道：Telegram

昨天我们用 `openclaw onboard` 把 Gateway 跑了起来，又经历了一场破壳仪式给小龙虾起了名字、填好了 IDENTITY / USER / SOUL 三件套。但说到底，它还只是一只关在终端里的小龙虾，尽管我们能在 TUI 里和它聊，也能在 WebChat 里和它聊，但这些都没有跳出传统 Chatbot 的范畴。

OpenClaw 真正区别于 ChatGPT、Open WebUI 这些工具的地方，是它长在你每天都打开的 IM 软件里。从今天开始我们正式接通道，第一站选 **Telegram**：只要一个 BotFather 给的 Token，不用扫码、不用 OAuth、不用绑手机号，是体验这套定位最快的一条路径。

## 为什么从 Telegram 开始

OpenClaw 支持 30 多个通道，从 WhatsApp、Signal、iMessage，到 Slack、Discord、飞书、企微，再到 Matrix、Nostr、IRC 这种小众选手。第一篇通道实战选哪个，其实是有讲究的。

我选 Telegram 的理由有三点：

第一，**接入门槛最低**。Telegram 的 BotFather 是一个聊天机器人，发 `/newbot` 就能拿到 Bot Token，整个过程不超过两分钟。WhatsApp 需要 WhatsApp Business API 或者扫码登录个人号，Signal 要绑定手机号，iMessage 要 macOS 配 BlueBubbles。Telegram 啥都不要，只要一个 Telegram 账号。

第二，**官方文档把它列为生产可用**。[OpenClaw 文档](https://docs.openclaw.ai/channels/telegram)中明确写着 `Production-ready for bot DMs and groups via grammY`，它使用 [grammY](https://grammy.dev/) 这个 TypeScript Bot 框架接的 Telegram Bot API，已经迭代好几年了，稳定性有保障。

第三，**国内外开发者用得最多**。OpenClaw 的 GitHub Discussions 和官方 Discord 里，关于 Telegram 的帖子是其他通道的好几倍，遇到坑也最容易搜到答案。

整体的消息流转大致是这样的：

![](./images/telegram-sequence.png)

注意这里 Gateway 是主动方，它通过 `getUpdates` 长轮询把消息从 Telegram 拉过来，而不是 Telegram 主动推过来。

> 其实，OpenClaw 支持 **长轮询** 和 **Webhook** 两种方式来接收 Telegram 消息。默认是长轮询模式，Gateway 主动调 `getUpdates`，不需要公网入口，本地开发最方便。要切到 Webhook，配置 `channels.telegram.webhookUrl` 加 `webhookSecret`，本地监听默认在 `127.0.0.1:8787`。Webhook 适合多副本部署或者无法长连接的环境，但需要反向代理把公网流量打进来，安全模型也要重新评估。

## 在 BotFather 注册一个 Bot

接 Telegram 的第一步是拿一个 Bot Token。这一步在 Telegram 端完成，跟 OpenClaw 无关。

打开 Telegram 客户端，搜索 `@BotFather`，要提醒一点的是，Telegram 上仿冒账号很多，如果认错了就相当于把 Token 交给骗子了，认准头像旁那个蓝色 ✓ 标志：

![](./images/bot-father.png)

进入 BotFather 的对话之后，发 `/newbot` 命令：

```
/newbot
```

BotFather 会让你依次输入两个东西：

1. **Bot 的展示名**：可以是中文，比如 `老钳`
2. **Bot 的 username**：必须以 `bot` 结尾且全局唯一，比如 `aneasystone_clawd_bot`

填完之后 BotFather 会回一条消息，里面有一行加粗的 token，长这个样子：

```
123456789:ABCdefGhIJKlmNOPQRsTUvWxYz0123456789
```

整段对话大致如下：

![](./images/clawd_bot.png)

> Bot Token 等同于这个 bot 的密码，**不要提交到 Git，不要贴到群里**。一旦泄露任何人都能冒充它收发消息，甚至替你操作 agent。如果不小心泄露了，回到 BotFather 发 `/revoke` 立刻吊销并重新生成一个就行。

顺手在 BotFather 里再做两个设置，等下接群组场景的时候用得到，现在做完就不用回来了：

* `/setprivacy` 选择目标 bot，把 Privacy Mode 设为 `Disabled`，让 bot 能看到群里的所有消息
* `/setjoingroups` 选择目标 bot，确认允许 bot 被加入群组

## 把 Token 配置进 OpenClaw

Token 拿到之后，OpenClaw 这边只需要把它写进 `~/.openclaw/openclaw.json`。在原有的配置基础上加一段 `channels.telegram`，最小可跑配置长这样：

```json
{
  "channels": {
    "telegram": {
      "enabled": true,
      "botToken": "123456789:ABCdefGhIJK...",
      "dmPolicy": "pairing",
      "allowFrom": []
    }
  }
}
```

以下对四个字段逐个解释：

`enabled` 是通道总开关，置为 `false` 就完全停用，配置项保留但 Gateway 启动时跳过初始化。开发期想临时关掉某个通道又不想丢配置，把这一行改成 `false` 重启即可。

`botToken` 就是刚才从 BotFather 拿到的 Token。除了写在配置里，OpenClaw 还支持两种替代方式：

* 环境变量 `TELEGRAM_BOT_TOKEN`，仅对默认账号生效
* `tokenFile` 字段，指向一个保存 Token 的纯文本文件

配置值优先级大于环境变量，生产环境推荐用 `tokenFile` 加文件权限保护（`chmod 600`），开发环境直接写在配置里最快。

`dmPolicy` 控制谁可以**直接给 bot 发私信**，有四个取值：

| 取值 | 行为 |
| ---- | ---- |
| `pairing` | 默认值，未知发送者会先收到一个一次性配对码，需要管理员审批才能放行 |
| `allowlist` | 严格白名单，必须在 `allowFrom` 里的数字 user ID 才能聊 |
| `open` | 完全开放，需要 `allowFrom` 显式包含 `"*"` 才生效 |
| `disabled` | 关闭私信入口 |

`allowFrom` 是数字形式的 Telegram user ID 列表，`telegram:` 和 `tg:` 前缀都会被自动归一化。空数组配 `pairing` 没问题，所有第一次进来的发送者都会走配对流程；空数组配 `allowlist` 则会被配置校验直接拒绝，因为这等于谁都不让进。

我们这里先用默认的 `pairing` 模式。这是 OpenClaw 推荐的姿势：默认把门关好，再按一个个配对码放人进来。

> 另外，不想手写 JSON 也可以用 `openclaw channels add --channel telegram --token <bot-token>` 命令增加新通道。

### 顺手扒一眼 grammY 整合

OpenClaw 是基于 [grammY](https://grammy.dev/) 接的 Telegram Bot API。我们扒一眼 `extensions/telegram/src/bot-core.ts`，能看到创建 Bot 实例的核心几行：

```ts
const bot = new botRuntime.Bot(opts.token, client ? { client } : undefined);
bot.api.config.use(botRuntime.apiThrottler());
bot.catch((err) => {
  runtime.error?.(danger(`telegram bot error: ${formatUncaughtError(err)}`));
});
```

这里做了简化，只保留了主要部分。可以看到 OpenClaw 没有自己造 SDK 的轮子，直接复用了 grammY 的 `Bot` 类，再叠加 `apiThrottler` 控制 API 调用速率，最后挂一个全局 `catch` 兜底。后面所有的 **messaging**（文本、图片、语音、视频、贴纸等消息的收发）、**reaction**（消息表情回应，长按消息冒出来的那一排 👍❤️🔥）、**forum topic**（超级群里把讨论分主题的频道分区，每个 topic 有独立的 `threadId`）、**inline button**（消息下方挂的那排可点按钮，点完会回传 `callback_data` 给 bot），都是在这个 grammY Bot 实例上扩出来的。

## 启动 Gateway 并验证

配置写完之后，重启 Gateway：

```
$ openclaw gateway restart
```

正常启动之后，先用 `openclaw doctor` 做一轮自检：

```
$ openclaw doctor
```

按上面那份默认 `pairing` 配置跑下来，可能在 `Security` 分块看到这一行：

```
- Telegram DMs: locked (channels.telegram.dmPolicy="pairing") with no allowlist;
  unknown senders will be blocked / get a pairing code.
  Approve via: openclaw pairing list telegram / openclaw pairing approve telegram <code>
```

这条信息的含义是：**Telegram 私信入口目前是上锁的**，未知发送者会被挡在外面、走配对流程。使用下面的命令检查一下 Telegram 通道状态：

```
$ openclaw channels status --probe
```

输出类似这样：

```
Gateway reachable.
- Telegram default: enabled, configured, running, connected, mode:polling, bot:@aneasystone_clawd_bot, token:config, works
```

如果你看到 `works` 或 `audit ok`，那就说明通道已经正常连接了；如果没看到类似的信息，而是报错了，这里列举了三种最常见的报错情况：

| 现象 | 排查方向 | 处置 |
| ---- | ------- | ---- |
| 报 `getMe returned 401` | 检查 token 配置源 | 重新复制或在 BotFather 里 `/revoke` 重新生成 |
| 报网络错误 | 看日志里 Telegram API 调用 | 修 DNS / IPv6 / 代理到 `api.telegram.org` 的链路 |
| 报 `getUpdates 409 Conflict` | 同一个 Token 多个 poller | 杀掉重复进程，必要时 `/revoke` Token |

> 注一：`api.telegram.org` 在国内直连不通。Telegram 通道支持标准代理环境变量 `HTTP_PROXY`、`HTTPS_PROXY`、`ALL_PROXY`，也支持配置里显式写 `channels.telegram.proxy: "socks5://user:pass@host:1080"`。文档里还提到 Node 22+ 的 `autoSelectFamily` 默认行为可能在 WSL2 上踩到 IPv6 优先的坑，可以通过 `channels.telegram.network.autoSelectFamily: false` 关掉。

> 注二：同一个 Bot Token 同时只能有一个 poller 在跑。如果重启 Gateway 后日志里持续报 409，多半是有另一个 OpenClaw 进程、调试脚本，或者 n8n / 其他 bot 框架也在用同一个 Token 调 `getUpdates`。先确认进程，再考虑去 BotFather `/revoke` 重置 Token。

接下来用自己手机给 bot 发第一条消息。

在 Telegram 里搜 `@aneasystone_clawd_bot`（替换成你自己的 username），点底部的 `开始` 按钮，发送一条 ”hello“ 消息。但你会发现一个意料之外的现象：bot 并没有正常回复你，而是回了一段类似下面的内容：

```
OpenClaw: access not configured.

Your Telegram user id: 7112345678
Pairing code:

ABC12345

Ask the bot owner to approve with:
openclaw pairing approve telegram ABC12345
```

如果对 OpenClaw 没有先验知识，看到这一幕第一反应是不是配错了？其实没错，这正是 OpenClaw 默认的安全策略在生效。

## DM 安全：pairing 配对机制

`dmPolicy="pairing"` 是 OpenClaw 给 Telegram 频道的默认 DM 策略，也是这一节的重点，理解这套机制对后面接其它通道也很有帮助。

> 这里顺带解释一下 **DM** 这个词。DM 是 Direct Message 的缩写，中文一般叫 **私聊** 或 **私信**，特指两个人之间一对一的对话，区别于群聊（group chat）。Telegram、Slack、Discord、Twitter 这些 IM 平台都用这个术语。后面文中反复出现的 `dmPolicy`、`dmScope`、"DM 授权"、"DM 入口"，凡是带 DM 字样的概念都是在说**私聊场景**。

### 默认行为

官方文档中对 pairing 的描述如下：

> When a channel is configured with DM policy `pairing`, unknown senders get a short code and their message is **not processed** until you approve.
>
> 当通道的 DM 策略配置为 `pairing` 时，未知发送者会收到一个短码，他们的消息在你审批之前**不会被处理**。

也就是说，**陌生人发来的私聊不会直接进 LLM**。OpenClaw 会先生成一个 8 位的配对码（大写、不含 `0O1I` 这种容易混的字符），把消息原文丢掉，回一段配对提示让对方拿着配对码去找管理员审批。配对码 1 小时过期，每个频道最多挂 3 个待审批请求，超出的会被忽略，避免被人刷码骚扰。

我们可以在 `extensions/telegram/src/dm-access.ts` 源码中找到对应的逻辑，关键片段如下：

```ts
if (allowed) {
  return true;
}

if (dmPolicy === "pairing") {
  // ... 创建配对请求并把配对码发回给陌生人
  await createChannelPairingChallengeIssuer({
    channel: "telegram",
    upsertPairingRequest: async ({ id, meta }) => ...,
  })({
    senderId: telegramUserId,
    senderIdLine: `Your Telegram user id: ${telegramUserId}`,
    sendPairingReply: async (text) => {
      await bot.api.sendMessage(chatId, html, { parse_mode: "HTML" });
    },
  });
  return false;
}
```

逻辑很清楚：先判断 sender 是不是已经在 `allowFrom` 或者 pairing-store 的允许列表里，是就直接放行；否则在 pairing 策略下生成一个 challenge，把配对码作为消息回给对方，最后 `return false` 阻止消息进入下游的 agent。

整条流程画成时序图就是这样：

![](./images/paring.png)

### 实操：approve 一个 sender

回到刚才的场景，由于我们是第一次和 bot 对话，对于 bot 来说我们还是陌生人，因此它会生成配对码并发给给我们。切回管理员的终端（就是我们自己），先列一下当前所有待审批的配对请求：

```
$ openclaw pairing list telegram

Pairing requests (1)
│ Code     │ telegramUserId │ Meta                                                              │ Requested                │
│ ABC12345 │ 7112345678     │ {"firstName":"Desmond","lastName":"Stonie","accountId":"default"} │ 2026-05-03T00:18:28.772Z │
```

输出里能看到配对码、Telegram user id、请求时间。确认是预期的人之后，approve 它：

```
$ openclaw pairing approve telegram ABC12345

Approved telegram sender 7112345678.
Config overwrite: ~/.openclaw/openclaw.json
Command owner configured telegram:7112345678 (commands.ownerAllowFrom was empty).
```

approve 之后 OpenClaw 把这个 Telegram user id 写进 `~/.openclaw/credentials/telegram-default-allowFrom.json`。从这一刻起，那个用户再发消息就会被放行，正常进入 agent，得到一个真正的 LLM 回复：

![](./images/first-reply.png)

这里有个细节，如果打开 `openclaw.json` 文件，你会发现你的 user id 被自动加到 `commands.ownerAllowFrom` 配置中了。OpenClaw 的官方文档说：如果你还没有配置过 command owner，**第一个被 approve 的 pairing 会顺手把这个 sender 写进 owner 列表**，让首次安装的用户能直接用 owner-only 命令（比如 `/exec`、`/config set`）和 exec approval。这个机制叫 first-owner bootstrap。后续 approve 就只授权 DM 访问，不再自动扩 owner。

### 危险动作：dmPolicy="open" + 通配符

很多教程为了演示方便会让用户改成 `dmPolicy: "open"` 加 `allowFrom: ["*"]` 一了百了。这等于把 bot 完全公开。

`dmPolicy: "open"` 配 `allowFrom: ["*"]` 意味着任何 Telegram 账号只要找到或猜到你的 bot username，就能命令这个 bot。如果你的 agent 接了 shell、文件系统或者花钱的工具，这个组合相当于把信用卡留在公共桌面上。官网的建议是：

> Use it only for intentionally public bots with tightly restricted tools; one-owner bots should use allowlist with numeric user IDs.
>
> 仅在有意公开、且工具被严格限制的 bot 上使用这个组合；一人自用的 bot 应当用 allowlist 配数字 user ID。

一人自用的 bot 推荐两条路：

1. **保持默认 `dmPolicy="pairing"`**：每个新设备/新身份过一次配对码审批，记录留在 `telegram-allowFrom.json` 里
2. **改成 `dmPolicy="allowlist"` + 显式数字 ID**：把自己的 Telegram user id 写进 `channels.telegram.allowFrom`，从此不再依赖 pairing-store

第二种适合稳定下来之后用，把访问策略固化进配置文件，后续即便清空了 pairing 状态目录也能继续用。要查自己的数字 user id，最省事的办法是在 Telegram 里搜索 `@userinfobot` 或 `@getidsbot`，让第三方 bot 告诉你。

## 群组场景

把 DM 跑通之后，把 bot 拉到一个 Telegram 群里，事情会比 DM 复杂一些。

### 隐私模式

Telegram bot 默认开着 **Privacy Mode**，群里的普通消息它根本看不到，只能收到 `@bot` 的显式提及和回复 bot 自己消息的那部分。这个限制在 Telegram 服务端，OpenClaw 拿不到，也修不了。

解决方法两种：

* 在 BotFather 里 `/setprivacy` -> `Disable`
* 或者把 bot 在群里设为管理员

任意一种都行。前面在 BotFather 那一节我们已经预先关过 Privacy Mode 了，但有一个细节要注意：**改完之后必须把 bot 从群里移出再重新加进来**，Telegram 才会让设置生效。

### 群组允许列表

默认 `groupPolicy` 是 `allowlist`，也就是说，只要你没显式列出哪些群，所有群消息都会被 Gateway 直接丢掉。最简单的开口子方式是允许任意群：

```json
{
  "channels": {
    "telegram": {
      "groups": {
        "*": { "requireMention": true }
      }
    }
  }
}
```

更稳妥的做法是只允许特定群（建议用真实 chat ID）：

```json
{
  "channels": {
    "telegram": {
      "groups": {
        "-1001234567890": { "requireMention": true }
      }
    }
  }
}
```

群的 chat ID 一般是带负号的长整数。怎么拿到？直接将群分享给 `@userinfobot` 即可获得。

### 触发模式：mention 还是 always

群里和 DM 最大的区别是触发方式，OpenClaw 用 `requireMention` 区分两种行为：

* `requireMention: true`（默认）：只有 `@bot` 提及、对 bot 消息的回复，或者匹配 `mentionPatterns` 的文本，才会触发回复
* `requireMention: false`：每条消息都进入 Agent，由模型自己判断要不要说话

还有一个等价的运行时命令，可以直接在群里临时切换：

```
/activation always
/activation mention
```

但这个只改当前会话的运行时状态，重启后就没了。要持久化必须写到配置文件。

### 遭遇群里 agent 不出声

把 chat ID、群 allowlist、`requireMention` 都配齐之后，我以为在群里 `@bot hello` 就能看到回复了，结果群里 bot 一片沉默。使用 `--verbose` 运行 gateway 日志才看到关键的一行：

```
Delivery suppressed by sourceReplyDeliveryMode: message_tool_only
for session agent:main:telegram:group:-1001234567890 — agent will still process the message
```

后面紧跟着 `turn ended without visible final response`，如果你也遇到类似的现象，可以看下日志中是否也有类似的提示。这说明 agent 收到了消息、跑完了一轮、也产出了文本，但是 OpenClaw 在投递这一步**主动把回包吞掉了**，群里自然听不到声响。

根据 OpenClaw 源码 `src/auto-reply/reply/source-reply-delivery-mode.ts` 中的逻辑，我们可以知道：

```ts
if (chatType === "group" || chatType === "channel") {
  const configuredMode =
    params.cfg.messages?.groupChat?.visibleReplies ?? params.cfg.messages?.visibleReplies;
  mode = configuredMode === "automatic" ? "automatic" : "message_tool_only";
}
```

群组和频道的默认 `visibleReplies` 是 `message_tool_only`，含义是 **agent 的普通文本输出不会自动发到群里**，只有当 agent 显式调 `message(action="send", ...)` 工具时才往群里发消息。而 DM 走的是另一个分支，默认 `automatic`，所以 DM 是好的，群里假死。

这个默认相当反直觉，但设计意图说得过去：群里更怕 bot 话痨刷屏，所以默认让 agent 自己决定"这一轮要不要在群里说话"，要说就显式调用 `message` 工具。

有两种解决方案：第一种保留默认，教 agent 主动调 `message` 工具，这需要对小龙虾进行调教，比如告诉它在群里要说话必须调 `message` 工具；第二种在配置文件里做一番修改，让 agent 文本直接进群，体验跟 DM 一模一样。我们这里使用第二种方案，在 `~/.openclaw/openclaw.json` 顶层（跟 `channels` 平级，**不要嵌进 `channels.telegram` 里**）加一段：

```json
{
  "messages": {
    "groupChat": {
      "visibleReplies": "automatic"
    }
  }
}
```

重启 Gateway 之后，再在群 `@bot` 打招呼，就能看到它的回复了。

## 媒体与富文本

Telegram 的消息类型不止文本，OpenClaw 把它们都规范化进了统一的 channel envelope，向 Agent 暴露的是结构化字段加占位符。

* **图片**：直接走多模态视觉模型，如果当前模型支持视觉（比如 GPT-5、Claude Sonnet 4.7、MiniMax M2.7-V）就能识别图片内容，否则会被替换成 `[image: ...]` 占位
* **语音**（voice note）：先做转写，转写文本以"机器生成、不可信"的标记注入上下文。提及检测会同时看原始转写，所以语音里 `@bot` 也能触发
* **视频**：和音频类似，会区分普通视频和 video note（圆形短视频）
* **贴纸**：静态 WEBP 会被下载并描述一次，结果缓存在 `~/.openclaw/telegram/sticker-cache.json` 里避免重复调视觉模型；动画 TGS 和 WEBM 视频贴纸暂时跳过

bot 回复的方向也支持这些类型。Agent 可以通过 `message` 工具调用反向产生媒体消息，比如下面这段 action JSON 让 bot 发一段语音：

```json
{
  "action": "send",
  "channel": "telegram",
  "to": "847291063",
  "media": "https://example.com/voice.ogg",
  "asVoice": true
}
```

加上 `[[audio_as_voice]]` 标签也能在普通回复里强制以 voice note 格式发出，配合后面要讲的 ElevenLabs TTS 几乎可以直接当随身语音助手用。

文本侧的输出走 `parse_mode: "HTML"`，OpenClaw 把模型产出的 Markdown 转成 Telegram 安全的 HTML 子集，遇到解析失败会自动 fallback 成纯文本，避免 bot 因为一个奇怪字符就哑掉。

关于这部分内容，我们后面在学习多模态和 Agent 工具调用的时候，再深入展开。

## 小结

通过这一篇，我们把 OpenClaw 的第一个 IM 通道接通了。回顾一下今天做的几件事：

1. **拿 Bot Token**：在 Telegram 里通过 BotFather 走 `/newbot` 流程，拿到形如 `123456789:ABC...` 的 token，顺手关掉 Privacy Mode、允许加入群组
2. **写最小配置**：在 `~/.openclaw/openclaw.json` 的 `channels.telegram` 下填 `enabled / botToken / dmPolicy / allowFrom`，重启 Gateway 就接通
3. **理解 grammY 整合**：OpenClaw 复用 grammY 的 `Bot` 类做长轮询，叠了 `apiThrottler` 和 `bot.catch`，没有自己造轮子
4. **理解 pairing 默认策略**：`dmPolicy="pairing"` 会让陌生人发来的 DM 收到配对码而不是直接进 LLM，管理员在终端用 `openclaw pairing approve telegram <CODE>` 显式批准，第一次 approve 还会自动把这个 sender 设成 owner
5. **避坑**：`dmPolicy="open"` 配 `allowFrom: ["*"]` 是把 bot 完全公开的危险动作，一人自用推荐保持默认 pairing 或者切成显式 allowlist + 数字 user id
6. **群组场景**：Privacy Mode 改完要把 bot 重新加群、`groupPolicy` 默认 allowlist 必须显式开口子、`requireMention` 控制群里是否一定要 `@bot`
7. **媒体与富文本**：图片走视觉模型、语音先转写、贴纸结果缓存；输出走 HTML，解析失败自动回退纯文本

至此小龙虾终于第一次出现在了一个真实的聊天软件里 —— 锁屏时、坐地铁时、开会摸鱼时，都可以直接在 Telegram 里发一句话过去。这才是 OpenClaw 想表达的 "长在你已有通道里" 的本意。

不过国内读者肯定会问：Telegram 在国内得挂梯子才能用，有没有更接地气的工作通道？答案是：有。OpenClaw 在 `extensions/feishu/` 对飞书做了原生支持，飞书走的是企业 OpenAPI 那一套自建应用流程，回调地址、加密验签、应用权限审批一样不缺，配置面要复杂不少，但跑通之后可以直接当工作助手用，特别适合每天泡在飞书里的同学。下一篇我们就来看看飞书的接入。

## 参考

* [OpenClaw Telegram 通道文档](https://docs.openclaw.ai/channels/telegram)
* [OpenClaw Pairing 机制文档](https://docs.openclaw.ai/channels/pairing)
* [OpenClaw Groups 文档](https://docs.openclaw.ai/channels/groups)
* [OpenClaw Group messages 文档](https://docs.openclaw.ai/channels/group-messages)
* [OpenClaw 通道排障指南](https://docs.openclaw.ai/channels/troubleshooting)
* [Telegram Bot API 官方文档](https://core.telegram.org/bots/api)
* [Telegram BotFather 介绍](https://core.telegram.org/bots/features#botfather)
* [grammY TypeScript Bot 框架](https://grammy.dev/)
