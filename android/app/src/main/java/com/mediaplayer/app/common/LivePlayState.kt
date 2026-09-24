package com.mediaplayer.app.common

import com.mediaplayer.core.source.model.LiveChannel

/**
 * 直播播放页的数据载体（应用进程内单例）。
 *
 * 放在 main（而非 flavor）里：手机端和电视端的播放页都要读它。
 * 为什么不用 Intent 传：频道列表可能上百条，Intent extra 有 1MB 左右的软上限，
 * 塞大列表是典型反模式。播放页生命周期内只读这一份快照，用完即弃。
 */
object LivePlayState {
    var channels: List<LiveChannel> = emptyList()
    var currentIndex: Int = -1
    var sourceName: String = ""
}
