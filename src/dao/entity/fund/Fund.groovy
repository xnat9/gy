package dao.entity.fund

import core.module.jpa.BaseEntity

import javax.persistence.Entity
import javax.persistence.Id

@Entity
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
}
