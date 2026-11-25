package com.zapp.net.notify

import com.zapp.net.R

/**
 * The 45 shipped sound files, id -> resource.
 *
 * Extracted from the installed OpenCode 2.3.2 build, not re-encoded: 26 came out of
 * `resources/app.asar` (`out/renderer/assets (every .aac file)`) and 19 out of the renderer chunks, where the
 * build inlines them as base64 data URLs. All 45 start with an ADTS sync word (`0xFFF`), which is
 * what Android's decoder needs; the asar extraction is the one that proved the header offset, since
 * reading at the naive base puts the previous entry's last 4 bytes in front of every file.
 *
 * The id is theirs (`"bip-bop-01"`, not `"bipbop-01"` - the label key drops the hyphen, the id
 * keeps it). [res] returns null for `"none"` and for anything unknown, which is what the preview
 * controller treats as "nothing to play".
 */
object SoundAssets {

    val ids: List<String> = listOf(
        "alert-01",
        "alert-02",
        "alert-03",
        "alert-04",
        "alert-05",
        "alert-06",
        "alert-07",
        "alert-08",
        "alert-09",
        "alert-10",
        "bip-bop-01",
        "bip-bop-02",
        "bip-bop-03",
        "bip-bop-04",
        "bip-bop-05",
        "bip-bop-06",
        "bip-bop-07",
        "bip-bop-08",
        "bip-bop-09",
        "bip-bop-10",
        "nope-01",
        "nope-02",
        "nope-03",
        "nope-04",
        "nope-05",
        "nope-06",
        "nope-07",
        "nope-08",
        "nope-09",
        "nope-10",
        "nope-11",
        "nope-12",
        "staplebops-01",
        "staplebops-02",
        "staplebops-03",
        "staplebops-04",
        "staplebops-05",
        "staplebops-06",
        "staplebops-07",
        "yup-01",
        "yup-02",
        "yup-03",
        "yup-04",
        "yup-05",
        "yup-06",
    )

    private val resources: Map<String, Int> = mapOf(
        "alert-01" to R.raw.snd_alert_01,
        "alert-02" to R.raw.snd_alert_02,
        "alert-03" to R.raw.snd_alert_03,
        "alert-04" to R.raw.snd_alert_04,
        "alert-05" to R.raw.snd_alert_05,
        "alert-06" to R.raw.snd_alert_06,
        "alert-07" to R.raw.snd_alert_07,
        "alert-08" to R.raw.snd_alert_08,
        "alert-09" to R.raw.snd_alert_09,
        "alert-10" to R.raw.snd_alert_10,
        "bip-bop-01" to R.raw.snd_bip_bop_01,
        "bip-bop-02" to R.raw.snd_bip_bop_02,
        "bip-bop-03" to R.raw.snd_bip_bop_03,
        "bip-bop-04" to R.raw.snd_bip_bop_04,
        "bip-bop-05" to R.raw.snd_bip_bop_05,
        "bip-bop-06" to R.raw.snd_bip_bop_06,
        "bip-bop-07" to R.raw.snd_bip_bop_07,
        "bip-bop-08" to R.raw.snd_bip_bop_08,
        "bip-bop-09" to R.raw.snd_bip_bop_09,
        "bip-bop-10" to R.raw.snd_bip_bop_10,
        "nope-01" to R.raw.snd_nope_01,
        "nope-02" to R.raw.snd_nope_02,
        "nope-03" to R.raw.snd_nope_03,
        "nope-04" to R.raw.snd_nope_04,
        "nope-05" to R.raw.snd_nope_05,
        "nope-06" to R.raw.snd_nope_06,
        "nope-07" to R.raw.snd_nope_07,
        "nope-08" to R.raw.snd_nope_08,
        "nope-09" to R.raw.snd_nope_09,
        "nope-10" to R.raw.snd_nope_10,
        "nope-11" to R.raw.snd_nope_11,
        "nope-12" to R.raw.snd_nope_12,
        "staplebops-01" to R.raw.snd_staplebops_01,
        "staplebops-02" to R.raw.snd_staplebops_02,
        "staplebops-03" to R.raw.snd_staplebops_03,
        "staplebops-04" to R.raw.snd_staplebops_04,
        "staplebops-05" to R.raw.snd_staplebops_05,
        "staplebops-06" to R.raw.snd_staplebops_06,
        "staplebops-07" to R.raw.snd_staplebops_07,
        "yup-01" to R.raw.snd_yup_01,
        "yup-02" to R.raw.snd_yup_02,
        "yup-03" to R.raw.snd_yup_03,
        "yup-04" to R.raw.snd_yup_04,
        "yup-05" to R.raw.snd_yup_05,
        "yup-06" to R.raw.snd_yup_06,
    )

    fun res(id: String?): Int? = id?.let { resources[it] }
}
