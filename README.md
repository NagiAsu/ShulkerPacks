# ShulkerPacks（NagiAsu fork）

這是 [christopherwalkerml/ShulkerPacks](https://github.com/christopherwalkerml/ShulkerPacks)（作者 Darkolythe，另有 Kerm、avixk 的貢獻）的 fork，從上游 1.8.0（commit `342dbb3`）分出來。授權照上游為 **GNU General Public License v3.0**（見 [LICENSE](LICENSE)），原作者的著作權不變。

This is a fork of [christopherwalkerml/ShulkerPacks](https://github.com/christopherwalkerml/ShulkerPacks) by Darkolythe (with contributions from Kerm and avixk), based on upstream 1.8.0. Licensed under GPL-3.0 like the original.

## 和上游不同的地方

版號在上游版號後加一位：`1.8.0.0` 起。插件名稱、資料夾（`plugins/ShulkerPacks/`）、package、指令、權限節點和上游相同，沿用上游的 `config.yml` 就能直接換 jar。

### 1.8.0.0

- **只能在玩家自己的背包畫面打開界伏盒**（或拿在手上對空氣右鍵，見 `canopeninair`）。箱子、終界箱、木桶、漏斗、投擲器、發射器、合成器、實體背包、其他插件的 GUI 一律不開
  - `canopeninchests`、`canopeninenderchest`、`canopeninbarrels`、`canopeninvirtualinventory`、`blacklistedinventories`、`open-previous-inventory` 不再使用（設定檔裡留著也沒關係）
- 開著的界伏盒改用「背包格子＋物品上的暫時標記」辨認，取代 `ItemStack.equals` 與介面標題比對；關閉時拿掉標記
- 開著時不能移動那個界伏盒（拿起、數字鍵、副手交換、丟出），也不能把界伏盒放進界伏盒。這些檢查在 LOWEST 優先度；開盒在 HIGH 優先度、略過其他插件已取消的點擊
- 每 tick 檢查：界伏盒已經不在玩家背包時，直接關掉介面、不寫回
- 開盒延到下一 tick，開之前重新檢查格子（避免用戶端背包顯示不同步）
- 斷線、死亡、插件停用時先把內容存回界伏盒
- 修正每 tick 檢查迴圈的 `ConcurrentModificationException`
- `shulker_open_cooldown` 缺鍵時的預設值改成 100 毫秒（和內附的設定檔一致；上游程式預設是 500）
- 在 plugin.yml 宣告 `shulkerpacks.admin`（預設 OP，收「開太快」「丟出開著的界伏盒」的警告）
- 建置：改用 paper-api 1.21.4、Java 21；拿掉沒有使用的 bStats 與寫死的輸出路徑；加上 GitHub Actions 編譯檢查
- 需要 **Paper** 1.21.4 以上（用到 Paper 的 API）。在 Paper 1.21.4 與 26.3 測試過

改過的原始檔開頭都有註解寫明誰、什麼時候、改了什麼。

## 建置

```bash
mvn -B package    # 產出 target/ShulkerPacks-<版號>.jar
```

---

## 上游原本的說明（Original README）

# ShulkerPacks
open shulker boxes without placing them down

![Image of Title](https://i.imgur.com/m1Y8zsr.png)

### Description:
Tired of having to put down your shulker to open it? Or maybe youre in claimed land? Now you can right click the shulker in your inventory to open it, or just right click the air with it in your main hand!

This also works in chests (GUIs can be blacklisted with their inventory name in the config)

### Permissions:
shulkerpacks.use (lets the player use shulker packs)

### Config:
```
blacklistedinventories: ["&4&lMineXChange", "test inventory"]

canopeninair: true            #false will prevent users from opening shulkerboxes by clicking the air
canopenininventory: true      #false will prevent users from opening shulkerboxes in their inventory
canopeninchests: true         #false will prevent users from opening shulkerboxes in chests
canopeninenderchest: true     #false will prevent users from opening shulkerboxes in enderchests
canopeninbarrels: true        #false will prevent users from opening shulkerboxes in barrels
canplaceshulker: true         #false will prevent users from placing shulkerboxes by accident

defaultname: "&9Shulker Pack"
shiftclicktoopen: false
shulkervolume: 1              #0.1 is quiet, 1 is loud
#shift click to open applies to both ingame and in inventory

```
__The fastest and easiest way to contact me:__
Discord: https://discord.gg/AET9mWj

![Image of Boost](https://i.imgur.com/8OAtOy5.png)

Can conflict with auto-sorting inventory plugins
