package dao.entity.fund

import core.module.jpa.BaseEntity

import javax.persistence.Entity
import javax.persistence.Index
import javax.persistence.Table

@Entity
@Table(indexes = [@Index(columnList = "code,date", unique = true)])
class FundHistory extends BaseEntity {
    String code
    // 单位净值
    Double unitPrice
    // 日期
    Date date
}
