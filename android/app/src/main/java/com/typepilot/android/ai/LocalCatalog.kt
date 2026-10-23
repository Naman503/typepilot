package com.typepilot.android.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

enum class ModelUse(val label: String) { AUTOCOMPLETE("Autocomplete"), REWRITE("Rewrite"), WRITE("Drafting"), TRANSLATE("Translation") }

/** Which LiteRT-LM backend a model file was built for. Many INT4 files are GPU specialists (slow on CPU). */
enum class PreferredBackend(val label: String) { CPU("CPU"), GPU("GPU"), BOTH("CPU or GPU") }

/**
 * A model that can be downloaded and run on the phone with LiteRT-LM.
 *
 * Speed numbers are estimates for a Dimensity 7200-class phone (scaled from published LiteRT-LM benchmarks on
 * Samsung S25/S26, Pixel 8a and a Dimensity 8350 phone); [DeviceProfile.speedFactor] adjusts them to this
 * phone, and "Benchmark" measures the real thing.
 */
data class LocalModelInfo(
    val id: String,
    val name: String,
    val publisher: String,
    val params: String,
    val repo: String,
    val file: String,
    val sizeMb: Int,
    val quant: String,
    val context: Int,
    val license: String,
    val backend: PreferredBackend,
    /** Peak process memory while generating, on the preferred backend (MB). */
    val peakRamMb: Int,
    /** Estimated decode speed (tokens/s) on a Dimensity 7200-class phone. */
    val estDecodeTps: Double,
    /** Estimated time to first token for a typical ~100-token suggestion prompt (s). */
    val estTtftSec: Double,
    /** 1–5: writing quality, from size and published results. */
    val quality: Double,
    val benchmark: String = "",
    val uses: Set<ModelUse>,
    val languages: String = "English",
    val notes: String,
    val gated: Boolean = false,
    val updated: String = "",
) {
    val url get() = "https://huggingface.co/litert-community/$repo/resolve/main/$file"
    val repoUrl get() = "https://huggingface.co/litert-community/$repo"
    val fileName get() = file
}

object LocalCatalog {
    val models = listOf(
        // ---------------- Light: instant autocomplete ----------------
        LocalModelInfo("smollm2-135m", "SmolLM2 135M Instruct", "Hugging Face", "135M", "SmolLM2-135M-Instruct", "SmolLM2_135M_Instruct.litertlm",
            143, "quantized", 0, "Apache-2.0", PreferredBackend.CPU, 350, 40.0, 0.2, 1.0,
            uses = setOf(ModelUse.AUTOCOMPLETE),
            notes = "The smallest model here. Finishes words and short phrases almost instantly; too small for real rewriting.", updated = "2026-06"),
        LocalModelInfo("gemma-3-270m", "Gemma 3 270M IT", "Google", "270M", "gemma-3-270m-it", "gemma3-270m-it-q8.litertlm",
            304, "INT8", 32_768, "Gemma terms", PreferredBackend.CPU, 500, 28.0, 0.3, 2.0, "IFEval 51.2",
            uses = setOf(ModelUse.AUTOCOMPLETE), languages = "Multilingual",
            notes = "Google's tiny model, very light on battery. Good at short, simple continuations. Needs a free Hugging Face token (accept the Gemma license on huggingface.co first).",
            gated = true, updated = "2026-04"),
        LocalModelInfo("smollm2-360m", "SmolLM2 360M Instruct", "Hugging Face", "360M", "SmolLM2-360M-Instruct", "SmolLM2_360M_instruct.litertlm",
            374, "quantized", 0, "Apache-2.0", PreferredBackend.CPU, 600, 22.0, 0.4, 1.5,
            uses = setOf(ModelUse.AUTOCOMPLETE),
            notes = "Tiny English model with decent grammar for its size.", updated = "2026-05"),
        LocalModelInfo("qwen3-0.6b-nothink", "Qwen3 0.6B (no-think)", "Alibaba Qwen", "0.6B", "Qwen3-0.6B-int4", "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
            347, "INT4 block-32", 1280, "Apache-2.0", PreferredBackend.CPU, 900, 10.0, 0.7, 2.5, "MMLU 52.8 (base)",
            uses = setOf(ModelUse.AUTOCOMPLETE, ModelUse.REWRITE), languages = "100+ languages",
            notes = "Thinking is pre-disabled, so answers come straight away (~2× quicker on CPU). Multiple prefill sizes keep short prompts fast. Best CPU pick for suggestions.",
            updated = "2026-06"),
        LocalModelInfo("granite-350m", "Granite 4.0 350M", "IBM", "350M", "granite-4.0-350m-litert-lm", "granite-4.0-350m_q8_ekv1280.litertlm",
            468, "INT8", 1280, "Apache-2.0", PreferredBackend.CPU, 650, 20.0, 0.5, 2.0,
            uses = setOf(ModelUse.AUTOCOMPLETE), languages = "12 languages",
            notes = "Business-friendly small model from IBM. Validated on CPU only.", updated = "2026-07"),
        LocalModelInfo("qwen3-0.6b", "Qwen3 0.6B (GPU build)", "Alibaba Qwen", "0.6B", "Qwen3-0.6B", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            345, "INT4 block-32 (GPU-optimised)", 4096, "Apache-2.0", PreferredBackend.GPU, 1800, 20.0, 0.4, 2.5, "MMLU 52.8 (base)",
            uses = setOf(ModelUse.AUTOCOMPLETE, ModelUse.REWRITE), languages = "100+ languages",
            notes = "Fast on the GPU (OpenCL) but slow on CPU. On MediaTek GPUs it uses more memory (~1.8 GB measured on a vivo phone).", updated = "2026-09"),

        // ---------------- Balanced: good rewriting ----------------
        LocalModelInfo("gemma-3-1b", "Gemma 3 1B IT", "Google", "1B", "Gemma3-1B-IT", "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
            584, "INT4", 4096, "Gemma terms", PreferredBackend.BOTH, 1100, 11.0, 0.8, 2.5, "GSM8K 62.8",
            uses = setOf(ModelUse.AUTOCOMPLETE, ModelUse.REWRITE), languages = "Multilingual",
            notes = "Solid small all-rounder. Needs a Hugging Face token (Gemma license).", gated = true, updated = "2026-09"),
        LocalModelInfo("lfm2.5-1.2b", "LFM2.5 1.2B Instruct", "Liquid AI", "1.2B", "LFM2.5-1.2B-Instruct", "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
            736, "INT4 block-32 (OCTAV)", 4096, "LFM Open License v1.0", PreferredBackend.BOTH, 1200, 13.0, 0.9, 3.5, "GSM8K 72% (int4)",
            uses = setOf(ModelUse.AUTOCOMPLETE, ModelUse.REWRITE, ModelUse.WRITE), languages = "8 languages",
            notes = "Hybrid conv + attention model built for phones. Liquid AI reports it beats Qwen3 1.7B and Gemma 3 1B. Runs on CPU and GPU (OpenCL). Best balance of speed and quality on 8 GB phones.",
            updated = "2026-10"),
        LocalModelInfo("minicpm5-1b", "MiniCPM5 1B", "OpenBMB", "1B", "MiniCPM5-1B", "minicpm_wi4b32_wi8_afp32_gpu_opt.litertlm",
            793, "INT4/INT8 mixed", 4096, "Apache-2.0", PreferredBackend.BOTH, 1200, 11.0, 1.0, 3.0,
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE), languages = "English, Chinese",
            notes = "New on-device model (thinking off in this build). GPU-optimised file.", updated = "2026-08"),
        LocalModelInfo("olmo2-1b", "OLMo 2 1B Instruct", "Allen AI", "1B", "OLMo-2-1B-Instruct", "OLMo-2-1B-Instruct_q4_block32_ekv4096.litertlm",
            931, "INT4 block-32", 4096, "Apache-2.0", PreferredBackend.CPU, 1300, 10.0, 1.2, 2.5,
            uses = setOf(ModelUse.REWRITE), languages = "English",
            notes = "Fully open model (data and training code published).", updated = "2026-09"),
        LocalModelInfo("qwen3-1.7b", "Qwen3 1.7B", "Alibaba Qwen", "1.7B", "Qwen3-1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            977, "INT4 block-32 (GPU-optimised)", 4096, "Apache-2.0", PreferredBackend.GPU, 1600, 12.0, 0.6, 3.5, "MMLU 62.6 (base)",
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE, ModelUse.TRANSLATE), languages = "100+ languages",
            notes = "Strong multilingual writer, including Hindi. A GPU specialist: ~3× faster than CPU, with GPU memory 2–3× lower. The first GPU load takes 4–8 s.",
            updated = "2026-08"),

        // ---------------- Heavy: best quality, needs free RAM ----------------
        LocalModelInfo("gemma-4-e2b", "Gemma 4 E2B", "Google", "2B effective (5B total)", "gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it-gpu.litertlm",
            2008, "2/4/8-bit mixed (QAT)", 32_768, "Gemma terms", PreferredBackend.GPU, 1800, 15.0, 0.5, 4.0,
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE, ModelUse.TRANSLATE), languages = "140 languages",
            notes = "Google's newest phone model, with the best writing quality that still fits 8 GB. Most embedding weights are memory-mapped (not held in RAM). Includes speculative decoding. The first GPU load takes several seconds.",
            updated = "2026-08"),
        LocalModelInfo("lfm2.5-2.6b", "LFM2.5 2.6B", "Liquid AI", "2.6B", "LFM2.5-2.6B", "LFM2.5-2.6B_int4.litertlm",
            1668, "INT4 block-32 (OCTAV)", 4096, "LFM Open License v1.0", PreferredBackend.BOTH, 2300, 8.0, 1.5, 4.0, "GSM8K 83% (int4)",
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE), languages = "8 languages",
            notes = "A reasoning model; TypePilot turns thinking off for fast writing. Close other apps on 8 GB phones.", updated = "2026-09"),
        LocalModelInfo("hy-mt2-1.8b", "Hunyuan MT2 1.8B", "Tencent", "1.8B", "Hy-MT2-1.8B", "Hy-MT2-1.8B_int8.litertlm",
            1827, "INT8", 4096, "Tencent Hunyuan", PreferredBackend.CPU, 2200, 7.0, 1.2, 3.5,
            uses = setOf(ModelUse.TRANSLATE), languages = "33 languages",
            notes = "Translation specialist. Use it for the English / हिन्दी rewrite modes.", updated = "2026-10"),
        LocalModelInfo("smollm3-3b", "SmolLM3 3B", "Hugging Face", "3B", "SmolLM3-3B", "SmolLM3-3B_q4_block32_ekv4096.litertlm",
            2005, "INT4 block-32", 4096, "Apache-2.0", PreferredBackend.CPU, 2600, 6.0, 1.8, 3.5,
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE), languages = "6 languages",
            notes = "Fully open 3B. Heavy for 8 GB phones.", updated = "2026-10"),
        LocalModelInfo("ministral-3-3b", "Ministral 3 3B Instruct", "Mistral AI", "3B", "Ministral-3-3B-Instruct-2512", "Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm",
            2343, "INT4 block-32", 4096, "Apache-2.0", PreferredBackend.CPU, 2700, 5.0, 2.0, 4.0,
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE), languages = "Multilingual",
            notes = "Excellent writer, but the model card says 8 GB phones should use CPU only and free RAM first, or Android may stop it.", updated = "2026-10"),
        LocalModelInfo("qwen3-4b", "Qwen3 4B Instruct 2507", "Alibaba Qwen", "4B", "Qwen3-4B-Instruct-2507", "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            2659, "INT4 mixed", 4096, "Apache-2.0", PreferredBackend.GPU, 4700, 6.0, 1.5, 4.5, "MMLU 73.0 (base)",
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE, ModelUse.TRANSLATE), languages = "100+ languages",
            notes = "Top quality, but measured at 4.7 GB peak on a MediaTek GPU. Needs 12 GB+ RAM.", updated = "2026-09"),
        LocalModelInfo("gemma-4-e4b", "Gemma 4 E4B", "Google", "4B effective (8B total)", "gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it-gpu.litertlm",
            2969, "2/4/8-bit mixed (QAT)", 32_768, "Gemma terms", PreferredBackend.GPU, 3300, 6.0, 1.0, 4.5,
            uses = setOf(ModelUse.REWRITE, ModelUse.WRITE, ModelUse.TRANSLATE), languages = "140 languages",
            notes = "Best Gemma for phones with 12 GB+ RAM.", updated = "2026-08"),
    )

    fun byId(id: String) = models.firstOrNull { it.id == id }
}

/** What this phone can run. */
data class DeviceProfile(
    val totalRamMb: Int,
    val availRamMb: Int,
    val soc: String,
    val cores: Int,
    val bigCores: Int,
    val maxGhz: Double,
    val hasOpenCl: Boolean,
    val freeStorageMb: Long,
    val model: String,
) {
    /** CPU throughput relative to a Dimensity 7200 (2× A715 @ 2.8 GHz + 6× A510): scales the speed estimates. */
    val speedFactor: Double get() {
        if (maxGhz <= 0.1) return 1.0 // clock speeds unreadable (some emulators/ROMs): keep the reference estimate
        val score = bigCores * (maxGhz / 2.8) + (cores - bigCores) * 0.35
        return (score / 4.1).coerceIn(0.4, 3.0)
    }

    enum class Fit(val label: String, val detail: String) {
        GREAT("Runs great", "Plenty of memory headroom"),
        GOOD("Works well", "Fits comfortably while other apps are open"),
        HEAVY("Heavy", "Close other apps first, or Android may stop it"),
        TOO_LARGE("Too large", "Needs more RAM than this phone has free"),
    }

    fun fit(m: LocalModelInfo): Fit {
        // Android, the launcher and recent apps use roughly 60–65% of RAM on a typical phone.
        val budget = totalRamMb * 0.38
        return when {
            m.peakRamMb <= budget * 0.6 -> Fit.GREAT
            m.peakRamMb <= budget -> Fit.GOOD
            m.peakRamMb <= totalRamMb * 0.5 -> Fit.HEAVY
            else -> Fit.TOO_LARGE
        }
    }

    fun estDecode(m: LocalModelInfo): Double = m.estDecodeTps * speedFactor * (if (m.backend == PreferredBackend.GPU && !hasOpenCl) 0.35 else 1.0)
    fun estTtft(m: LocalModelInfo): Double = m.estTtftSec / speedFactor * (if (m.backend == PreferredBackend.GPU && !hasOpenCl) 3.0 else 1.0)

    /** The best pick per use for this phone: highest quality that fits well and stays fast enough. */
    fun recommended(use: ModelUse): LocalModelInfo? = LocalCatalog.models
        .filter { use in it.uses && !it.gated && fit(it) <= Fit.GOOD && (use != ModelUse.AUTOCOMPLETE || estTtft(it) <= 0.8) }
        .maxByOrNull { it.quality * 10 + estDecode(it) / 10 }

    companion object {
        fun read(context: Context): DeviceProfile {
            val am = context.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1_000_000.0 }.getOrNull()
            }.filter { it > 0.1 }
            val max = freqs.maxOrNull() ?: 0.0
            val big = if (freqs.isEmpty()) 0 else freqs.count { it >= max * 0.85 }.coerceAtLeast(1)
            val openCl = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so",
                "/vendor/lib64/egl/libGLES_mali.so").any { File(it).exists() }
            val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE
            val stat = StatFs(context.filesDir.absolutePath)
            return DeviceProfile(
                totalRamMb = (mi.totalMem / 1_048_576).toInt(),
                availRamMb = (mi.availMem / 1_048_576).toInt(),
                soc = soc, cores = Runtime.getRuntime().availableProcessors(), bigCores = big, maxGhz = max,
                hasOpenCl = openCl, freeStorageMb = stat.availableBytes / 1_048_576,
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }
    }
}

/** A speed test on this phone, saved per model. */
data class BenchmarkResult(val modelId: String, val backend: String, val loadMs: Long, val ttftMs: Long, val decodeTps: Double, val memMb: Int, val at: Long)
