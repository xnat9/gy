package sevice.fund

import cn.xnatural.enet.event.EL
import core.module.OkHttpSrv
import core.module.ServerTpl
import core.module.jpa.BaseRepo
import dao.entity.fund.Fund

import javax.annotation.Resource

class FundSrv extends ServerTpl {

    @Resource OkHttpSrv http
    @Resource BaseRepo repo

    @EL(name = 'sys.started')
    def start() {
        initFund('161024')
    }


    protected initFund(String code) {
        if (repo.count(Fund, {root, query, cb -> cb.equal(root.get('code'), code)}) > 0) return
        String url = "http://fund.eastmoney.com/pingzhongdata/${code}.js"
        String r =http.get(url).execute()
        println r.split("Data_netWorthTrend")[1].split(";")[0].split("=")[1]
    }

}
