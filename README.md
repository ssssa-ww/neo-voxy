# Neo-Voxy REFORGED

This very experimental port of Neo-Voxy is a community effort to bring LOD rendering and streaming natively to NeoForge. Special starting points are owed to [voxy-server-side](https://modrinth.com/plugin/voxy-server-side) which helped model the Fabric version of LOD streaming.

Tons of thanks to **cortex** (original author of Voxy) and **xantha** for cleaning up and optimizing the core architecture.

---

> ⚠️ **EXPERIMENTAL PORT** - This is an experimental NeoForge port of the original Fabric Voxy mod. **Use at your own risk!**

Neo-Voxy is a NeoForge port of the Voxy mod, a far-distance rendering mod utilizing LODs (Level of Detail) for massive render distances. This Reforged edition integrates VSS (Voxel Streaming Service) directly, adding native client-server synchronization, extensive memory optimizations, and localization.

## Core Features & What's New

1. **Voxel Streaming Service (VSS) Integration**
   * Native server-to-client LOD synchronization.
   * Automatic client cache invalidation: if a server world is reset, generated, or changed, the client automatically clears its local cache (`.voxy/saves/<server_ip_port>`) based on a persistent server world UUID (`vss_world_uuid.txt`) to prevent rendering glitches.

2. **Severe Memory Leak & Performance Fixes**
   * **Zero-Memory Chunky Generation**: Replaced heavy coordinate queues with an event-driven delta-scanner. No queue data is kept in RAM during Chunky runs. It triggers a background scan of modified region files on completion.
   * **Region Index Flattening**: Refactored `RegionIndex` to use flat primitive arrays, eliminating up to **8.3 million long-lived heap objects** (`IndexSlot` instances).
   * **GC Pressure Optimization**: Replaced composite map keys with packed primitive `long` keys using Fastutil.
   * **Deflater & Inflater Pooling**: Reuses native zlib `Deflater` and `Inflater` streams via thread-locals to stop native buffer churn and fragmentation.

3. **Advanced Client Controls**
   * **LOD Propagation Speed Settings**: A slider in the Sodium settings screen (supporting **Slow**, **Standard**, **Fast**, **Extreme**, **Ludicrous**, and **Uncapped**) dynamically scales pending section checks (8 to 2048 checks/tick) and queue drains.
   * **Client-Side Rejoin Cache Loading**: Pre-loads saved section positions on rejoin, enabling correct Bloom filter negotiation to avoid re-downloading already-saved LOD chunks.

4. **Singleplayer & Integrated Server Enhancements**
   * **Singleplayer Background Auto-Ingestor**: Enabled background MCA region scanning on the integrated server thread so Chunky pre-generated chunks are correctly processed in singleplayer.
   * **Autosave Database Integration**: Auto-flushes and saves active LOD cache directories whenever Minecraft triggers a save/autosave or `/save-all`.

5. **Multi-Language Localizations**
   * Comprehensive translations for **English**, **French**, **German**, **Spanish**, **Russian**, **Japanese**, **Brazilian Portuguese**, **Simplified Chinese**, and **Traditional Chinese**.

## Current State

| Feature | Status |
|---------|--------|
| LOD Streaming | ✅ Functional / Highly Optimized |
| Server-to-Client Sync | ✅ Functional |
| Shader Support (Iris) | ✅ Voxy Shaders Functional |
| Singleplayer Chunky Ingest | ✅ Functional |
| Auto Cache Invalidation | ✅ Functional |

## Requirements

| Dependency | Required Version |
|------------|------------------|
| Minecraft | `1.21.1` |
| NeoForge | `21.1.77+` |
| Sodium | `0.6.0+` (NeoForge edition) |
| Iris | Required (NeoForge edition) |

## Installation

1. Install NeoForge 21.1.77 or later for Minecraft 1.21.1
2. Install Sodium for NeoForge (version 0.6.0+)
3. Install Iris Shaders for NeoForge
4. Place the Neo-Voxy jar in your mods folder

## Removed Mixins

The following mixins from the original Fabric Voxy are currently **removed** in this NeoForge port:

### Flashback Integration (Removed)
| Mixin | Purpose |
|-------|---------|
| `flashback.MixinFlashbackMeta` | Flashback recording metadata integration |
| `flashback.MixinFlashbackRecorder` | Flashback recording system integration |

### Nvidium Integration (Removed)
| Mixin | Purpose |
|-------|---------|
| `nvidium.MixinRenderPipeline` | Nvidium render pipeline compatibility |

### Other Removed Mixins
| Mixin | Purpose |
|-------|---------|
| `minecraft.MixinGlDebug` | OpenGL debugging utilities |

## Current Mixin Configuration

### Client Mixins (`voxy.mixins.json`)

* **Minecraft Core**: `MixinWorld`, `MixinClientChunkCache`, `MixinClientCommonPacketListenerImpl`, `MixinClientLevel`, `MixinClientPacketListener`, `MixinFogRenderer`, `MixinLevelRenderer`, `MixinMinecraft`, `MixinRenderSystem`, `MixinWindow`, `MixinLayerLightSectionStorage`
* **Sodium Integration**: `AccessorChunkTracker`, `AccessorSodiumWorldRenderer`, `MixinChunkJobQueue`, `MixinDefaultChunkRenderer`, `MixinRenderSectionManager`, `MixinSodiumOptionsGUI`, `MixinSodiumWorldRenderer`
* **Iris Integration**: `CustomUniformsAccessor`, `IrisRenderingPipelineAccessor`, `MixinIris`, `MixinIrisRenderingPipeline`, `MixinIrisSamplers`, `MixinLevelRenderer`, `MixinMatrixUniforms`, `MixinPackRenderTargetDirectives`, `MixinProgramSet`, `MixinShaderPackSourceNames`, `MixinStandardMacros`

### Common Mixins (`voxy-common.mixins.json`)
* `MixinLevelCommon` - Server/common level hooks

### VSS Compatibility Mixins (`vss.compat.mixins.json`)
* Covers entity tracking accessors, teleport commands, level chunk bindable tickers, and depth mask rendering integration.

## Known Issues
* ⚠️ **INCOMPATABILITIES**: BetterFpsDist.

## Contributing

This is an unofficial port. If you encounter issues:
1. First check if the issue exists in the original Fabric version
2. If it's port-specific, document the issue with steps to reproduce
3. Pull Requests are welcome for fixing NeoForge compatibility issues

## Credits

- **Original Voxy Mod**: [cortex](https://github.com/cortex/voxy)
- **LOD Streaming Protocol (VSS)**: [xantha](https://github.com/xantha/voxy-server-side) with my deprecated source
- **NeoForge Reforged Port**: Community effort

## License

```
Copyright 2025 MCRcortex
All rights reserved. Do not redistribute.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

> ⚠️ **IMPORTANT**: This is a source-code-only fork. **Do not distribute compiled builds.** You must build from source yourself.

---

**Version**: `0.1.0`  
**Minecraft**: `1.21.1`  
**Mod Loader**: NeoForge `21.1.77+`
