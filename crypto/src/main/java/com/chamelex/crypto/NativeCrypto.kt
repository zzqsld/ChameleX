package com.chamelex.crypto

/**
 * Native 破解算法 JNI 接口。
 * darkside/nested/staticnested 在 libchamelex_crypto.so，hardnested 在 libchamelex_hn.so。
 * 所有恢复函数都可能耗时较长，必须在后台线程（Dispatchers.Default）调用。
 */
object NativeCrypto {

    init {
        System.loadLibrary("chamelex_crypto")
        System.loadLibrary("chamelex_hn")
    }

    /**
     * Darkside 恢复。
     * @param acquisitions 扁平数组 [nt1, par, ks1, nr, ar]×N（对应 CLI darkside_list 累积语义）
     * @return 候选密钥数组（48 位值），空 = 未恢复（继续采集后重试）
     */
    external fun darksideRecover(uid: Long, acquisitions: LongArray): LongArray

    /**
     * Nested（弱随机卡）恢复。
     * @param nonces 扁平数组 [nt, nt_enc, par]×N
     * @return 候选密钥数组（按命中次数排序，需逐个 MF1_AUTH 验证）
     */
    external fun nestedRecover(uid: Long, dist: Long, nonces: LongArray): LongArray

    /**
     * StaticNested（固定随机数卡）恢复。
     * @param targetType 目标密钥类型（0x60=A / 0x61=B）
     * @param nts 扁平数组 [nt, nt_enc]×N
     * @return 候选密钥数组（需逐个 MF1_AUTH 验证）
     */
    external fun staticNestedRecover(uid: Long, targetType: Int, nts: LongArray): LongArray

    /**
     * HardNested 恢复（可能耗时数分钟，用 [hardnestedCancel] 取消、[hardnestedProgress] 轮询进度）。
     * @param keyTypeTarget 0=A / 1=B
     * @param noncesText nonce 文本（每行 "nt_enc|par_enc"，与官方 nonce 文件格式一致）
     * @param cacheDir 可写目录（用于落盘 nonce 文件）
     * @return 恢复的密钥（48 位），-1 = 失败/取消
     */
    external fun hardnestedRecover(
        uid: Long,
        blockTarget: Int,
        keyTypeTarget: Int,
        noncesText: String,
        cacheDir: String,
    ): Long

    external fun hardnestedCancel()

    /** 当前进度，格式 "nonces|bruteForce|activity"。 */
    external fun hardnestedProgress(): String

    /**
     * MFKey32(v2) 侦测反推：由同一 (uid, 块, 密钥类型) 下的两条认证记录反推读卡器密钥。
     * @return 48 位密钥，-1 = 未找到
     */
    external fun mfkey32Recover(
        uid: Long,
        nt0: Long, nr0: Long, ar0: Long,
        nt1: Long, nr1: Long, ar1: Long,
    ): Long

    /** 候选密钥验证（模拟一次认证，检查 reader response）。 */
    external fun mfkey32Verify(uid: Long, nt: Long, nr: Long, ar: Long, key: Long): Boolean

    /** 算法库自检（0=通过）。 */
    external fun selfTest(): Int

    /** hardnested 库自检（xz 解压状态表，0=通过）。 */
    external fun hardnestedSelfTest(): Int
}
