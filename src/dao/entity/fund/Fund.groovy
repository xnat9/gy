package dao.entity.fund

import core.module.jpa.BaseEntity
import org.hibernate.annotations.DynamicUpdate

import javax.persistence.Entity
import javax.persistence.Id

@Entity
@DynamicUpdate
class Fund extends BaseEntity {
    @Id
    String code
    String name
    // 类型. 例如: 混合型, 股票型
    String type
    // 基金成立日
    Date start
    // 基金规模
    Double total
    // 最新值
    Double newestUnitPrice
    // 多少元起购
    Double lowestPurchase
    // 最大单价
    Double maxUnitPrice
    // 最低单价
    Double minUnitPrice
    // 是否可用
    Boolean available
    // 描述
    String comment

    //================分析的数据=====================

    // 最新与最低的差价
    Double spread_newest_min
    // 最高与最新的差价
    Double spread_max_newest
    // 近一个月平均值
    Double lastMonthAvg
    // 连续降的次数
    Integer continuousDownCount
    // 连续升的次数
    Integer continuousUpCount
    // 最近10天 下降的 次数
    Integer downCountOfTen
    // 最近30天 下降的 次数
    Integer downCountOf30day
    // 涨(与最新一条历史的价相比)
    Boolean up

    // ===============自定义属性================

    // 我的自选 观察
    Boolean watch
    // 通知价格
    Double notifyUnitPrice
}
