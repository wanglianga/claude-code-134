package com.trialflow.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "app.business")
class BusinessProps {
    /** 顾问提成比例 */
    var commissionRate: Double = 0.05

    /** 退费时已消耗赠课是否按原价抵扣 */
    var giftConsumedDeducts: Boolean = false

    /** 渠道费率 */
    var channelRates: Map<String, Double> = emptyMap()

    fun channelRate(channel: String): Double = channelRates[channel] ?: 0.0
}
