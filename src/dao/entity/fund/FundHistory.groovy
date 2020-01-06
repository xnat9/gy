package dao.entity.fund

import core.module.jpa.BaseEntity

import javax.persistence.Entity
import javax.persistence.Id

@Entity
class FundHistory extends BaseEntity {
    @Id
    String code
    Double unitPrice
    Date date
}
