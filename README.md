# TerrainLab：自然地形采集与游戏内预览

中文 | [English](README.en.md)

TerrainLab 是一个独立的 **Paper 26.3 / Java 25** 插件，用于先建立地形数据集，再为后续 NPC 避险实验准备场景。当前阶段只采集方块和生物群系标签：采集过程不创建实验 NPC、不引爆 TNT，也不保存动物、怪物或其他来源实体。只有预览沙盒可以通过显式命令 `/terrain tnt` 生成 TNT，沙盒中的结果不会保存。它与先前的 `NpcLab` 使用不同插件名、命令和数据目录，可以同时安装。

每个样本包含以候选中心为原点的 **25×25×11 核心区域**，以及默认 **41×41×25 上下文区域**。样本保存为 UTF-8 JSON 的 gzip 文件，可以用 Python 直接读取，也可以在游戏内逐个查看或按九宫格分页展示。

**这份数据描述局部地形。画廊提供视觉预览，目前不构成原版物理过程的严格回放。** 例如，海面上方的开阔位置可以被采集，但并不保证村民能在那里站立，也不保证点燃的 TNT 会停留在中心。

## 1. 安装与第一次采集

### 环境

- 服务端：Paper 26.3。本项目编译依赖固定为 `paper-api:26.3.build.218-stable`。
- Java：JDK 25。Paper 官方当前安装文档将 26.1 及之后版本对应到 Java 25。[Paper 安装说明](https://docs.papermc.io/paper/getting-started/)
- 客户端：连接该服务器的 Minecraft Java Edition 客户端。
- 权限：OP，或权限节点 `terrainlab.admin`。采集和状态命令也可在服务器控制台使用；预览命令需要在游戏内执行。

### 使用提供的 JAR

从 [GitHub Releases](https://github.com/ChenXuYangJP/terrain-lab-starter/releases/tag/v0.3.0) 下载预编译的 `terrain-lab-0.3.0.jar` 和 `SHA256SUMS.txt`，放在同一目录下可以先校验文件：

```bash
sha256sum -c SHA256SUMS.txt
```

在服务器控制台执行 `stop`，等待服务器正常退出，然后将 JAR 复制到服务器 `plugins` 目录：

```bash
cp terrain-lab-0.3.0.jar /path/to/paper-server/plugins/
```

把示例路径改成自己的服务器目录，按原来的方式启动 Paper。首次启动会生成 `plugins/TerrainLab/config.yml`。服务端日志应出现 `TerrainLab ready`。

从旧版本升级时，先删除 `plugins` 中旧的 JAR（例如 `terrain-lab-0.2.0.jar`），再复制新 JAR，否则 Paper 会发现两个同名插件而拒绝重复加载。`plugins/TerrainLab/` 中的配置、样本和任务文件保持不变，旧版本启动的任务仍可继续。

这个 JAR 是服务端插件，放在 Paper 服务器的 `plugins` 目录，不需要放进客户端。普通 Minecraft 客户端可以连接和观看。

### 先采一个样本

在游戏里依次执行：

```text
/terrain catalog
/terrain start 101 1 overworld minecraft:plains
/terrain status
```

第一条命令列出运行服务器的目标生物群系数量，并写入 `plugins/TerrainLab/catalog.json`。第二条命令新建一个任务：使用 seed `101`，只在主世界的 `minecraft:plains` 中尝试保存一个合格样本。出现 `Saved sample_...` 后，再执行：

```text
/terrain list 1
/terrain show sample_实际样本ID core
```

把 `sample_实际样本ID` 换成 `/terrain list 1` 输出的完整 ID。预览完成后执行：

```text
/terrain leave
```

先确认这一条完整流程成功，再扩大采集规模。这里的“成功”包括保存 gzip、能够读取检查、能够进入预览和离开预览，而不只是服务器接受了命令。

## 2. 采集规模与 biome 数量

不要把早期的“主世界 54 + 下界 5 + 末地 5”固定写进数据集。26.2 新增了 Sulfur Caves，26.3 新增了 Dappled Forest；版本、功能开关和数据包都会影响可用内容。[26.2 官方说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2) · [26.3 官方说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)

本插件从运行服务器的 biome registry 读取条目，再使用 `minecraft:is_overworld`、`minecraft:is_nether`、`minecraft:is_end` 标签形成采集目标，排除 `minecraft:the_void`。**已注册、属于某个维度标签、能在指定世界中找到合格位置，是三件不同的事。** 标签中有某个 biome，不保证有限搜索预算内能找到它，也不保证该世界的生成器实际会生成它。[Paper Biome API](https://jd.papermc.io/paper/26.3/org/bukkit/block/Biome.html) · [Registry 文档](https://docs.papermc.io/paper/dev/registries/)

在默认 26.3 内容下，可按 56 个主世界、5 个下界、5 个末地，即 **66 个候选目标**做预算；实际数量以你运行 `/terrain catalog` 的输出为准。下面的数字都表示计划配额，最终样本可能有缺额。

| 阶段 | 命令 | 若目标数为 66，计划数量 |
|---|---|---:|
| 单样本检查 | `/terrain start 101 1 overworld minecraft:plains` | 1 |
| 全类型试采 | `/terrain start 101 1` | 66 |
| 约 500 个样本 | `/terrain start 101 8` | 528 |
| 五个 seed，每类十个 | `/terrain start 101,202,303,404,505 10` | 3,300 |

`/terrain start` 的完整语法：

```text
/terrain start <seed1,seed2,...> <每类数量> [all|overworld|nether|end] [biome_key]
```

seed 使用十进制有符号 64 位整数。最多接受 20 个不同 seed；每类配额为 1～100。省略维度时使用配置中的维度列表。输入多个 seed 时，逗号两侧不要加空格。

每次 `start` 都创建一个新的 `job_id`，并使用该任务独立的来源世界。即使 seed 一样，新任务也不会接着旧任务计数。要继续旧任务，使用 `resume`。

### 从 worldseeds.yml 读取 seed

也可以把 seed 写在 `plugins/TerrainLab/worldseeds.yml` 中，再用 `startyml` 启动任务：

```text
/terrain startyml <每类数量> [all|overworld|nether|end] [biome_key]
```

首次启动插件时会生成这个文件，内容只有注释和一份被注释掉的示例。取消注释并修改 `seeds` 列表即可：

```yaml
seeds:
  - "101"
  - "202"
  - "303"
  - "404"
  - "505"
```

此时 `/terrain startyml 10` 与 `/terrain start 101,202,303,404,505 10` 完全相同，可选的维度和 biome 参数也一样。seed 规则与 `start` 相同：十进制有符号 64 位整数，1～20 个，重复的会被忽略。建议给每个 seed 加引号：YAML 会把未加引号、以 0 开头的数字当作八进制，过大的数字也可能无法按整数读取。每次执行 `startyml` 都会重新读取该文件，不需要重启服务器。

### 续采：-continue

不加 `-continue` 时，新任务与旧任务完全独立：每类数量只计本任务，而且候选搜索只由世界 seed、biome 和维度决定，所以相同 seed 的新任务会重走旧任务的搜索路径，先采到与旧任务相同的位置。这是为了可复现而有意如此。

在 `start` 或 `startyml` 末尾加上 `-continue`，新任务会接着已有样本采集：

```text
/terrain startyml 9 -continue
/terrain start 101,202 9 overworld -continue
```

- **补足到 N。** 已有样本计入每类数量，每个 seed × 维度 × biome 只补足差额。例如 seed 101 的 plains 已有 1 个，`startyml 9 -continue` 再采 8 个，合计 9 个；已有 9 个或更多的组合直接跳过。上次因找不到合格位置而缺额的组合会在本次重试，并获得新的候选预算。重复位置只计一次。所有组合都已满足时，命令会拒绝启动。
- **避开已有中心。** 已有样本就是 `samplingseed.yml` 中列出的那些（来自 `jobs/` 的任务记录）。新中心与这些 seed 下的已有中心之间使用与任务内相同的规则：同一维度内至少相距 48 格，上下文区域不能重叠，不区分 biome。因此被拒绝的候选在 report CSV 中记为 `near_or_overlapping_earlier_job_sample`。
- **新的搜索顺序。** 续采任务使用新的随机盐，不会重走旧任务的路径；盐保存在任务文件中，`resume` 时行为一致。

服务器刚启动时插件需要先读取全部任务记录，此时 `-continue` 会提示稍后重试。用 `clearsamples` 清除后不再有已有样本，`-continue` 相当于普通任务（但搜索顺序仍是新的）。

### samplingseed.yml：采样中心记录

插件维护 `plugins/TerrainLab/samplingseed.yml`，按世界 seed 分组列出每个已保存样本的中心坐标和地形类型：

```yaml
world_seeds:
  "101":
    - sample: "sample_a0535c4b703a358872cadc05"
      job: "job_mhf2k3ab_1a2b3c4d"
      dimension: "overworld"
      biome: "minecraft:plains"
      center: [1234, 72, -567]
```

`center` 是样本中心在该 seed、该维度中的方块坐标，与样本 metadata 的 `source_origin_xyz` 相同；`biome` 是该中心被接受时请求的 biome。文件来自 `jobs/` 中的任务 checkpoint，包括 `start` 和 `startyml` 启动的所有任务，在服务器启动时和每保存一个样本后整体重写，因此手动修改会被覆盖；删除样本文件不会删除这里的记录。在另一个世界中按坐标找回地形时，还需要相同的 Minecraft 版本、数据包和生成设置（见第 7 节）。

要检查已保存的中心是否靠得太近，可以对这个文件运行重叠检查工具，只需要 Python 3：

```bash
python3 tools/check_sample_overlap.py /path/to/paper-server/plugins/TerrainLab/samplingseed.yml
python3 tools/check_sample_overlap.py /path/to/samplingseed.yml --json
python3 tools/check_sample_overlap.py /path/to/samplingseed.yml -moveout
```

两个样本属于同一世界 seed 和同一维度，且 25 × 25 × 11 的核心区域至少共有一个方块时，视为重叠。默认模式列出每一对重叠样本及其行号；`--json` 以 JSON 输出同样的结果。`-moveout` 保留每对中（按文件顺序）的第一个样本，把其余样本移到同目录的 `samplingseed_overlaped.yml`，该文件已存在时追加写入。由于插件在服务器启动时和每保存一个样本后都会重写 `samplingseed.yml`，被移出的条目之后会重新出现；样本文件和任务 checkpoint 不会被修改。退出码：0 表示没有重叠（或 `-moveout` 成功），1 表示发现重叠，2 表示文件无法读取、格式错误或写入失败。

要找出无法进行 TNT 与村民实验的采样中心，可以运行支撑检查工具。它从同目录的 `samples/`（或 `--samples DIR`）读取每个条目的样本文件，同样只需要 Python 3：

```bash
python3 tools/check_tnt_support.py /path/to/paper-server/plugins/TerrainLab/samplingseed.yml
python3 tools/check_tnt_support.py /path/to/samplingseed.yml --json
python3 tools/check_tnt_support.py /path/to/samplingseed.yml -moveout
```

只检查 25 × 25 × 11 的核心区域。任一检查不通过，样本就会被标记：

- **TNT：** 放在中心的 TNT 会穿过草、花、一层雪、藤蔓等没有碰撞箱的方块向下落。落入水、岩浆或其他液体，或一直到核心底部都没有可停留的方块，则不通过。
- **村民：** 需要在距中心水平 1–8 格（欧氏距离，可用 `--radius` 修改）的某一列中，有一个位于 dy −5 到 +3 的站立方块，且其上方两格空闲，这样村民整体都在核心区域内。站立方块必须有碰撞箱，且不是液体、岩浆块、仙人掌、营火、栅栏或墙；上方两格不能有碰撞箱、液体或危险方块。

碰撞判断来自脚本中的方块名表，而非 Minecraft 的实际形状；表中未知的方块名一律视为实心。`-moveout` 把被标记的条目移到同目录的 `samplingseed_unsupported_terrain.yml`，该文件已存在时追加写入。样本文件缺失或与条目不一致的条目会被报告并保留。与重叠检查一样，插件下次重写 `samplingseed.yml` 时被移出的条目会重新出现。退出码：0 表示没有被标记的条目（或 `-moveout` 成功）且所有条目都已检查，1 表示仍有被标记的条目留在文件中或有条目无法检查，2 表示文件无法读取、格式错误或写入失败。

## 3. 位置筛选实际保证什么

默认筛选条件是：中心附近 **3×3 的水平范围、向上 2 格全部为空气**，中心正下方一格不是空气。整块 25×25 区域不需要全部开阔，所以周围的坡地、树木、岩壁、水面和洞穴仍会保留下来。

默认 `site.require-solid-support: false`，因此正下方可以是水、熔岩、树叶等自然方块。这样能保留水域和植被场景，避免为了凑齐 biome 配额而人工铺平台。样本会记录：

- `capture_eligible: true`：满足这次地形采集规则。
- `support_blockdata`、`support_is_liquid`、`support_kind`：中心下方的实际方块及分类。
- `require_solid_support`、`clearance_radius`、`clearance_height`：此次任务使用的筛选条件。

这些字段**不等于 NPC 可站立、逃生路径存在，或 TNT 可稳定放置**。后续创建 NPC 时需要额外检查实体碰撞箱、脚下支撑、头顶空间、液体与危险方块、可达路径和起始距离。

如果希望只接受保守的完整方块支撑，可以在 `plugins/TerrainLab/config.yml` 中设置：

```yaml
site:
  clearance-radius: 1
  clearance-height: 2
  require-solid-support: true
```

此时使用完整立方体碰撞形状判断支撑，而不是只看 `Material.isSolid()`。这仍然只是局部几何条件，不自动排除一切有伤害或特殊行为的方块，也不会自动生成“安全 NPC 起点”。修改配置并重启服务器后，新任务使用新设置；恢复已有任务使用其 checkpoint 中保存的设置。

候选中心会留出完整 context 的上下空间，避免在世界高度边界外静默补空气。下界候选中心另限制在 `Y ≤ 115`，避免把基岩顶面以上的大片空气当作常规下界场景。

海洋可以采到水面上方的空气位置。不过，核心区域只包含中心以下 5 格，**不能用它推断整个深海的深度、海底结构或完整水柱**。默认 context 向下也只有 12 格；如果研究问题依赖更深环境，需要更大的上下文或保留原始来源世界。

## 4. 采样、缺额与独立性

采样过程使用有限预算：先选随机搜索原点，调用 biome 定位；再在定位结果附近选候选 X/Z 列；对每列的合法 Y 区间逐层检查，并用 reservoir sampling 在该列满足条件的高度中选一个。它可以覆盖地下 biome，而不是始终只取最高表面。

**这不是整个世界或整个 biome 上的均匀随机采样。** 定位算法、搜索范围、邻域半径和“有空气开阔位置”的资格条件都会影响分布。reservoir sampling 的均匀性仅针对已经选中的那一列中的合格高度。

默认每个 `seed × dimension × biome` 桶最多尝试 160 个候选；定位次数也有上限。找不到目标、候选不合格、中心太近、context 重叠或预算用尽，都可能产生缺额。程序不会修改地形来补数。可以用：

```text
/terrain status
```

查看当前目标、尝试次数和拒绝计数。每个任务还输出：

```text
plugins/TerrainLab/jobs/<job_id>-report.csv
```

CSV 中包含 `seed, dimension, biome, target, accepted, shortfall, candidates, locates, finished, rejections`。一个任务显示 `complete`，表示所有桶都结束了尝试，不保证每个桶都达到配额。

默认最小中心距离为 48 格，同时拒绝上下文包围盒重叠。这些检查只针对**同一任务内、同一 seed 和维度**的已有样本。它们降低空间重复，但不能证明统计独立；不同任务仍可能生成相同或相似地形。

`terrain_sha256` 用于标识相同的上下文方块数据，当前不会仅因为 hash 相同就自动丢掉样本。同样的平地可能出现在不同 biome 中；是否合并需要结合研究目标决定。这个 hash 不包含 biome 标签，也不衡量“近似相同”。

以后做训练/验证/测试划分时，优先按 seed 或完整场景分组；同一地形的不同 NPC 起点、不同动作、重复爆炸试验应留在同一个分组。不要把相邻 tick 或同一场景的不同试验随机拆到训练和测试两侧。各 biome 相同配额适合比较覆盖情况，但不代表自然世界出现频率；报告效果时应区分按 biome 平均和按实际部署分布加权。

## 5. 暂停、恢复与任务文件

```text
/terrain pause
/terrain resume
/terrain resume job_具体任务ID
/terrain status
```

`pause` 停止继续推进采样，但已经开始的那个样本可以继续读取、压缩并保存。看到状态不再忙碌后再启动新任务。`stop` 是 `pause` 的命令别名，不代表删除数据。

不带 ID 的 `resume` 读取 `latest-job.txt` 指向的任务；带 ID 时恢复指定 checkpoint。若程序已发布一个完整样本，但尚未来得及把它计入任务，恢复逻辑会核对样本和任务信息后补记。未完成发布的临时文件不算样本。

任务文件保存了采集设置和 Minecraft 版本。恢复时不会悄悄采用新配置；Minecraft 版本改变时应启动新任务，避免混合版本。

| 路径 | 内容 |
|---|---|
| `plugins/TerrainLab/config.yml` | 新任务默认设置 |
| `plugins/TerrainLab/catalog.json` | 最近一次导出的 registry 与维度标签 |
| `plugins/TerrainLab/samples/sample_*.json.gz` | 完整地形样本 |
| `plugins/TerrainLab/jobs/job_*.json.gz` | 任务 checkpoint |
| `plugins/TerrainLab/jobs/job_*-report.csv` | 各桶产量、缺额和拒绝计数 |
| `plugins/TerrainLab/latest-job.txt` | 最近任务 ID |
| `plugins/TerrainLab/gallery-state.yml` | 预览区域恢复信息 |
| `plugins/TerrainLab/worldseeds.yml` | `startyml` 使用的世界 seed 列表（用户编辑） |
| `plugins/TerrainLab/samplingseed.yml` | 按世界 seed 分组的采样中心坐标和地形类型（由任务 checkpoint 生成） |

来源世界和预览世界还会占用 Minecraft 的世界存档空间。插件使用自己的世界 key、所有权标记和 UUID 校验管理这些世界；遇到不能确认归属的已有世界会拒绝接管。暂停、换页和结束任务不会自动删除来源存档。

### 清除全部样本

```text
/terrain clearsamples
/terrain clearsamples confirm <确认码>
/terrain clearsamples cancel
```

第一步会列出将要删除的文件数量，并给出一个 6 位确认码，60 秒内有效，只能由同一位玩家（或控制台）使用。第二步必须输入这个确认码才会执行删除；确认码错误或过期都会取消本次请求，需要重新执行第一步。任务正在运行或忙碌时会拒绝清除，请先 `/terrain pause` 并等待。

删除是**永久**的，范围是：`samples/` 中的全部样本（包括中断保存留下的临时文件）、`jobs/` 中的任务 checkpoint 和 report CSV，以及 `latest-job.txt`；`samplingseed.yml` 随后重写为空。之后 `/terrain resume` 没有可恢复的任务。`worldseeds.yml`、`config.yml`、`catalog.json` 和预览状态会保留。来源世界和预览世界的存档也不会删除；如需释放这部分磁盘空间，需要另行处理。预览中已经粘贴的方块会保留到下一次 `show` 或 `gallery`。

## 6. 核心区域和上下文区域

下表的尺寸按 **X × Z × Y** 表述；文件中的数组形状按 **Y、Z、X** 记录。

| 区域 | 空间尺寸 | 相对中心范围 | `shape_yzx` | 格数 |
|---|---|---|---|---:|
| core | 25×25×11 | X/Z：−12～12；Y：−5～5 | `[11,25,25]` | 6,875 |
| context | 41×41×25 | X/Z：−20～20；Y：−12～12 | `[25,41,41]` | 42,025 |

**core 完全包含在 context 内。** 默认实际覆盖的唯一空间是 42,025 格，不能再加上 6,875 当作新的空间。为了方便模型直接读取核心窗口，文件同时提供两份数组，因此文件中会重复保存 core 对应的索引。

25×25×11 可以作为固定模型输入，但不能单凭这个尺寸断言其覆盖了全部 TNT 影响。Paper API 中 `power=4F` 对应 TNT，并不意味着所有方块破坏、实体伤害、击退和后续地形变化都被严格限制在“4 格半径”内。[Paper World API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)

特别是上下各 5 格会裁掉部分洞顶、下方地层、流体和支撑结构。逃生路径可能绕出水平边界；爆炸后掉落、流动或重力方块还可能把边界影响继续传进来。**41×41×25 也只是当前保存上下文的工程起点，没有经验证的“足够大”保证。** 未来要用原位置或更大上下文做对照试验，记录 NPC、路径和方块变化是否触及边缘，必要时扩大范围或将试验标成边界受限。

## 7. 文件格式与信息保真范围

每个样本是 `sample_<安全ID>.json.gz`。gzip 解压后为 UTF-8 JSON，顶层键固定为：

```text
schema_version
id
metadata
core
context
```

当前 `schema_version` 为 `1`。每个 grid 的键如下：

| 字段 | 含义 |
|---|---|
| `shape_yzx` | `[sizeY,sizeZ,sizeX]` |
| `min_offset_xyz` | 最小相对坐标 `[minDx,minDy,minDz]` |
| `axis_order` | 固定为 `"y,z,x"`，X 变化最快 |
| `palette` | 完整 BlockData 字符串列表，例如 `minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]` |
| `indices` | 每格指向 `palette` 的非负整数 |
| `biome_palette` | namespaced biome key 列表 |
| `biome_indices` | 每格对应的 biome 标签索引 |

给定相对坐标 `(dx,dy,dz)`，索引为：

```python
index = ((dy - minDy) * sizeZ + (dz - minDz)) * sizeX + (dx - minDx)
blockdata = palette[indices[index]]
```

对 core，可直接写成：

```python
index = ((dy + 5) * 25 + (dz + 12)) * 25 + (dx + 12)
```

不要把不同 grid 的 palette 数字当作全局方块 ID。索引 `0` 在两个文件里可能指向不同方块。需要先查各自的 palette；检查 core/context 一致性时也按实际字符串比较。

### 元数据

`metadata` 保存 seed、原始世界与维度、中心绝对坐标、Minecraft/Paper/data version、采集起止时间和 tick、世界时间与天气、难度、gamerules、功能开关、已启用数据包名、插件列表、筛选规则和采样设置等。seed 始终是**十进制字符串**，避免被其他语言的浮点 JSON 读取器舍入。

数据包目前只保存名称，没有保存内容摘要；`datapack_content_fingerprint` 明确记为未知。因此“seed 相同”不等于在不同版本、不同数据包或不同生成配置下能重建相同世界。

biome 有两个相关概念：中心采样资格按 `getComputedBiome` 判断，并保存 `center_computed_biome`；每格数组保存 `World.getBiome` 返回的底层 noise biome 标签，中心另有 `center_noise_biome`。两者在边界附近可能不同，不能不加区分地把数组当成每格 F3 显示结果。[Paper RegionAccessor API](https://jd.papermc.io/paper/26.3/org/bukkit/RegionAccessor.html)

以下字段是当前格式的明确约束：

```json
{
  "entities_omitted": true,
  "block_entity_nbt_omitted": true,
  "scheduled_ticks_omitted": true,
  "fidelity": "blockdata_only",
  "capture_atomic": false
}
```

方块朝向、阶梯形状、含水状态等 BlockData 会保存。箱子的库存、告示牌文字、刷怪笼设置等额外方块实体 NBT 不保存；实体、计划中的方块/流体 tick、运行时随机状态也不保存。体素采集跨多个服务端 tick 分批进行，因此记录了开始/结束 tick，不能称为某一个瞬间的原子世界快照。

文件通过同目录临时 gzip 完整写入、关闭后，再以不覆盖目标的硬链接发布。读者不会把半写入文件当成已完成样本；已存在的样本 ID 会拒绝覆盖。这个实现要求样本所在文件系统支持硬链接，不把突然断电后的磁盘持久性作为保证。

`metadata.terrain_sha256` 对完整 context 的尺寸、偏移和按 Y/Z/X 顺序解码后的 BlockData 做 SHA-256；如果没有 context，则对 core 做。它与 palette 排序无关，不包含 biome 和其他 metadata。Java 与 Python 读取器都会核验已声明的 hash。

## 8. 用 Python 检查样本

不需要安装 NumPy 或启动 Minecraft。系统有 Python 3 即可：

```bash
python3 tools/inspect_terrain.py /path/to/paper-server/plugins/TerrainLab/samples/sample_实际ID.json.gz
```

需要机器可读摘要时：

```bash
python3 tools/inspect_terrain.py /path/to/sample_实际ID.json.gz --json
```

工具检查 gzip 完整性、JSON 字段、seed 与坐标类型、数组尺寸、palette 索引、core/context 逐格一致性和已声明的地形 hash，并输出中心方块、中心底层 biome、两层尺寸和 hash。检查成功退出码为 `0`；不合法为 `2`。通过结构检查不表示 Minecraft 物理回放已经验证。

在自己的 Python 程序里读取核心数组：

```python
import gzip
import json

with gzip.open("sample_实际ID.json.gz", "rt", encoding="utf-8") as f:
    sample = json.load(f)

grid = sample["core"]
dy, dz, dx = 0, 0, 0
index = ((dy + 5) * 25 + (dz + 12)) * 25 + (dx + 12)
center_block = grid["palette"][grid["indices"][index]]
center_biome = grid["biome_palette"][grid["biome_indices"][index]]
seed = int(sample["metadata"]["seed"])
print(center_block, center_biome, seed)
```

这个简短示例只演示字段读取；批量处理前应先通过检查器验证输入。

## 9. 单独查看与九宫格展示

```text
/terrain list 1
/terrain show sample_实际ID core
/terrain show sample_实际ID context
/terrain gallery 1 core
/terrain gallery 2 core
/terrain gallery 1 context
/terrain goto 5
/terrain leave
```

`list` 每页列出 10 个 ID；`gallery` 每页最多放 9 个样本，编号 1～9。页码从 1 开始，样本按 ID 排序，不保证与采集时间顺序一致。`show` 只展示指定样本，省略模式时默认为 `core`。`goto` 跳到当前页指定位置，`leave` 恢复进入预览前的返回位置和游戏模式。

预览使用单个共享的专用 NORMAL 世界，所有来源维度都摆到约 `Y=96` 的展示高度；实际锚点保留源坐标模 4 的对齐。九宫格中心间隔为 64 格。玩家以 spectator 模式观看（下文的 `play` 沙盒除外），插件分批粘贴方块，并尽量抑制预览中的爆炸、放置破坏、流动和方块更新。换页会更新共享展示，不是每位玩家各有一份独立场景。

保持 biome 格点对齐有助于展示，但预览世界的 seed、绝对坐标、维度和周围环境已变化，所以 F3 的 computed biome、混色、天空与光照仍可能不同。核对原始标签时看样本 metadata 和 `biome_indices`。

预览省略了额外 NBT 和计划 tick，也没有复制来源维度的完整运行状态。关闭随机 tick、取消部分事件、用 `applyPhysics=false` 粘贴，均不能证明所有原版机制都被冻结。**请把它用于观察地形形状、比较遮挡和检查采样质量；未来的爆炸实验需要专门的回放与复位流程。**

### 沙盒游玩模式

```text
/terrain play
/terrain play 3
/terrain tnt
/terrain tnt 1 20
/terrain villagerai
/terrain villagerhp
/terrain reset
/terrain goto 1
/terrain leave
```

预览完成后，`play [格位]` 在共享预览中打开沙盒：先在当前页每个样本外侧铺一层基岩外壳，再把执行者切换为 creative，传送到指定格位（默认 1）的样本中心。之后可以放置和破坏方块、倒水、用刷怪蛋生成生物，方块物理按正常规则运行：重力方块下落、流体流动、活塞推动、TNT 和苦力怕破坏方块、火焰蔓延。沙盒期间开启生物破坏、火焰蔓延和掉落物；随机刻仍为 0，因此作物不生长，被裁断的树叶也不会自然腐烂；自然刷怪、昼夜和天气仍保持关闭。

基岩外壳厚 1 格：底板紧贴样本最底层下方，四面侧墙立在底板上，比样本最顶层高 2 格，顶部敞开。外壳只存在于预览世界，不属于样本数据。

沙盒内的方块变化只允许发生在“编辑盒”内，即样本本身的范围加上其上方 16 格的建造空间。编辑盒以外的方块变化会被取消，包括外壳本身、漫过墙顶的流体和相邻格位，因此 creative 玩家也不能拆除外壳。实体和玩家可以离开编辑盒，掉进周围虚空的生物会消失。

`tnt [格位] [引信tick]` 在指定格位的样本中心（采集时的候选中心）生成一个已点燃的 TNT。默认格位 1、引信 80 tick（约 4 秒），引信范围 0～600。只有沙盒打开时才能使用；爆炸只破坏编辑盒内的方块，插件不记录爆炸结果。

预览世界中生成的村民默认不使用原版行为：插件把它们设为 unaware，因此不会自行走动、工作、睡觉、聚集或逃跑，但仍受重力、推挤、伤害和击退影响（Paper 说明 unaware 生物还可能关闭溺水等未列明行为）。`villagerai [on|off]` 切换这一设置，不带参数时在开和关之间切换；切换会立即作用于预览世界中已有的村民，之后生成的村民也按新设置处理。默认关闭，服务器重启后恢复为关闭。只影响村民，不影响其他生物，也不影响其他世界。

`villagerhp [on|off]` 在预览世界中每个村民头顶显示当前生命值，例如 `❤ 17.5 / 20`：高于 50% 为绿色，高于 25% 为黄色，其余为红色。受到伤害或回复生命后，标签在下一 tick 更新；死亡时显示 0。切换方式与 `villagerai` 相同，不带参数时在开和关之间切换，并立即作用于已有村民和之后生成的村民。默认关闭，服务器重启后恢复为关闭。标签使用村民的名称显示，开启期间会覆盖命名牌设置的名字；关闭时只清除插件设置的标签。只影响村民。

**沙盒改动永远不会写回 `samples/`。** 画廊只读取样本文件，改动只发生在预览世界中。`reset` 从磁盘重新读取当前展示的样本并重新粘贴，清除所有改动、外壳、生物和掉落物；任何新的 `show` 或 `gallery` 也会先结束沙盒并做同样的清理。外壳和建造空间在铺设前已记入 `gallery-state.yml`，即使服务器在游玩中崩溃，下一次展示仍会清理它们。重新渲染时，预览中的玩家会切回 spectator，`leave` 仍恢复进入前的位置和游戏模式。

沙盒是共享场景，所有在预览中的玩家看到同一份改动。`goto` 回到 spectator 观察视角；再次执行 `play` 直接回到沙盒，不会重铺外壳。

外壳是人为边界，样本边缘处的流体、重力方块和光照与来源世界不同。例如裁断面上的水会被侧墙挡住；最底层的重力方块由基岩底板而不是原有地层支撑。沙盒仍然不是原版物理的严格回放；其中的 TNT 只用于目视检查，不是未来爆炸实验所需的回放与复位流程。

## 10. 性能、存储和配置

区块生成、来源世界保存和 biome 搜索通常比压缩一个局部 JSON 更重。来源世界存档可能远大于样本文件；新的任务会创建新的来源世界。没有依据机器性能、目标分布和实际运行结果，不能承诺 528 或 3,300 个样本的完成时间。

默认设置包括：

| 配置项 | 默认值 | 作用 |
|---|---:|---|
| `collection.max-candidate-attempts` | 160 | 每个桶的候选预算 |
| `collection.candidates-per-locate` | 16 | 每次定位附近的候选数 |
| `collection.locate-radius` | 4096 | 单次定位半径 |
| `collection.random-origin-range` | 60000 | 随机定位原点范围 |
| `collection.candidate-radius` | 96 | 定位点附近的候选范围 |
| `collection.minimum-center-distance` | 48 | 同任务同 seed/维度的最小中心距离 |
| `collection.capture-blocks-per-tick` | 1000 | 每 tick 的体素读取数量上限 |
| `collection.concurrent-chunk-loads` | 2 | 并行区块加载数量上限 |
| `collection.cooldown-ticks` | 10 | 采集步骤之间的节流 |
| `preview.blocks-per-tick` | 2000 | 每 tick 的预览粘贴数量上限 |

体素读取还受每 tick 的时间预算约束；世界和方块访问留在服务端主线程，后台只压缩、校验和写入已经复制出的纯数据。但 `locateNearestBiome` 本身是同步调用，限半径和增加 cooldown 不能保证单次调用不造成卡顿。[Paper World API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)

如果先运行一个样本就明显卡顿，优先缩小定位半径或候选搜索规模，观察服务器状态后再决定是否扩大；更小预算也可能增加缺额。不要一次把所有预算调大来追求凑满配额。

任务运行期间，插件会暂时禁止 Paper 在服务器无人时自动暂停，以免采集停止；暂停、完成或插件关闭时会释放这个设置。服务器进程仍必须保持运行。

context 的 X/Z 可配置为 25～59 的奇数，Y 可配置为 11～63 的奇数，保证包含固定 core 并适配当前画廊。修改范围会改变存储、生成负担和样本选择条件，应启动新任务记录新设置。

## 11. 在 VS Code 修改与构建

用 VS Code 打开整个 `terrain-lab-starter` 文件夹，编辑 `.java`、`config.yml` 或 `plugin.yml`。JAR 是编译后的产物，通常不直接编辑里面的字节码。

| 文件 | 主要职责 |
|---|---|
| `src/main/java/io/github/terrainlab/TerrainLabPlugin.java` | 命令、采集状态机、checkpoint |
| `BiomeCatalog.java` | 运行时 biome registry 与维度标签 |
| `SiteFinder.java` | 候选中心、空气空间和支撑筛选 |
| `OwnedWorlds.java` | 专用来源/预览世界与所有权核验 |
| `TerrainSnapshot.java` | 纯数据模型、坐标索引与一致性验证 |
| `SnapshotIO.java` | gzip JSON、原子发布、hash 与读取 |
| `SeedFiles.java` | 读取 `worldseeds.yml`，生成 `samplingseed.yml` |
| `Gallery.java` | 游戏内单样本和分页展示、沙盒游玩模式 |
| `tools/inspect_terrain.py` | 无 Minecraft 依赖的样本检查 |
| `tools/check_sample_overlap.py` | 找出 `samplingseed.yml` 中核心区域重叠的采样中心 |
| `tools/check_tnt_support.py` | 找出无法放置 TNT 或 1–8 格内没有村民站位的采样中心 |
| `tools/test_tnt_support.py` | TNT/村民支撑检查工具的自测，只需 Python |
| `tools/test_snapshot_io.py` | Java/Python 格式与文件行为自测 |
| `tools/test_owned_metadata.py` | Paper 世界身份元数据 NBT 解析与拒绝逻辑自测 |
| `pom.xml` | Java、Paper API 和 Gson 构建依赖 |
| `README.en.md` | 本文档的英文版 |

确保 `java -version`、`javac -version` 和 `mvn -version` 都使用 JDK 25，再在项目根目录执行：

```bash
mvn package
```

新 JAR 位于：

```text
target/terrain-lab-0.3.0.jar
```

修改代码后的安装操作应复制这个新的 `target` 文件；`dist` 中的预编译副本不会因你编辑源码而自动更新。

```bash
cp target/terrain-lab-0.3.0.jar /path/to/paper-server/plugins/
```

复制前正常停止服务器，复制后重新启动。源码编译时依赖 Gson 2.14.0 和 Paper API；运行时由 Paper 提供对应依赖。

可以单独执行不依赖 Bukkit 的格式自测：

```bash
python3 tools/test_snapshot_io.py --jdk /path/to/jdk-25 --gson /path/to/gson-2.14.0.jar
```

如果 `JAVA_HOME` 已指向 JDK 25，且 Maven 缓存中有 Gson 2.14.0，通常直接运行 `python3 tools/test_snapshot_io.py` 即可。测试会在临时目录真实编译两个 Java 数据类并写入样本，再用 Python 读取，不会操作服务器世界。

## 12. 验证范围与后续实验

当前交付的验证范围是项目编译，以及纯数据的 Java/Python 自测。自测覆盖 gzip/UTF-8 往返、64 位 seed、原始 BlockData、所有核心索引、palette 重排不影响 hash、裁切一致性、坏 gzip、错误 schema、负索引、NaN、重复 JSON key、拒绝覆盖和并发原子发布。

已实际使用 JDK 25 和 Paper API `26.3.build.218-stable` 完成 Maven 构建。8 项快照格式测试全部通过；另有 13 项世界身份元数据解析测试通过，覆盖标准 NBT 的 UUID 读取和损坏、过大、过深等输入的拒绝。后者可以在 Maven 构建后运行：

```bash
python3 tools/test_owned_metadata.py --jdk /path/to/jdk-25 --maven-repo /path/to/local-maven-repository
```

元数据测试使用临时合成 NBT 文件和实际编译类，不启动服务器，也不代表真实世界创建与重载已验证。

**没有在真实 Paper 服务器里完成端到端运行验证，也没有随项目提供真实采样数据。** 测试中的合成数据只是临时格式夹具，不是从 Minecraft 世界采集的训练场景。因此应从第一节的单个 plains 样本开始验证你本机的版本、世界生成、保存、预览和返回流程。

0.3.0 通过了 Maven 构建和上述自测。在本地 Paper 26.3 服务器上执行过 `startyml`、`-continue` 和 `clearsamples`，并对生成的 `samplingseed.yml` 运行过 `tools/check_sample_overlap.py`。预览沙盒命令 `play`、`tnt`、`villagerai`、`villagerhp` 和 `reset` 也在该服务器上执行过。服务器日志中没有 TerrainLab 错误。基岩外壳、编辑范围和爆炸还没有系统验证。首次使用沙盒时，请按第 9 节在单个样本上确认其行为是否符合预期。

后续接入 NPC 避险时，需要增加：实际可站立起点与目标点、实体/路径边界记录、保留原维度和绝对高度的实验世界、每次试验的完整复位、TNT 与 NPC 的初始状态、动作请求与实际运动的区分、伤害后的真实生命值、终止原因和多次重复试验。对于水域、熔岩、重力方块、红石或额外 NBT 相关场景，还需要专门设计可重复的回放策略。

固定 seed 有助于记录世界生成来源，但不单独保证运行时 AI、爆炸随机过程和计划更新完全相同。只有把回放条件、缺失信息和边界影响都纳入试验，后续采集到的行动结果才适合可靠比较。

## 官方参考

- [Minecraft Java Edition 26.2：Sulfur Caves](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2)
- [Minecraft Java Edition 26.3：Dappled Forest](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
- [Paper 安装与 Java 版本要求](https://docs.papermc.io/paper/getting-started/)
- [Paper Registry API](https://docs.papermc.io/paper/dev/registries/)
- [Paper 26.3 Biome](https://jd.papermc.io/paper/26.3/org/bukkit/block/Biome.html)
- [Paper 26.3 BiomeTagKeys](https://jd.papermc.io/paper/26.3/io/papermc/paper/registry/keys/tags/BiomeTagKeys.html)
- [Paper 26.3 World：定位、区块、爆炸等 API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)
- [Paper 26.3 RegionAccessor：noise/computed biome](https://jd.papermc.io/paper/26.3/org/bukkit/RegionAccessor.html)
- [Paper 26.1：多世界存储结构变化](https://papermc.io/news/26-1/)
