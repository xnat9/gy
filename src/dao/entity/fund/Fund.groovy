package dao.entity.fund

import core.module.jpa.BaseEntity

import javax.persistence.Entity
import javax.persistence.Id

@Entity
class Fund extends BaseEntity {
    @Id
    String code
    String name
}
