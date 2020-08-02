import cn.xnatural.enet.event.EL
import core.AppContext
import core.module.EhcacheSrv
import core.module.OkHttpSrv
import core.module.Remoter
import core.module.SchedSrv
import core.module.jpa.HibernateSrv
import ctrl.FundCtrl
import ctrl.MainCtrl
import ctrl.ratpack.RatpackWeb
import dao.entity.fund.Fund
import dao.entity.fund.FundHistory
import groovy.transform.Field
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import service.TestService
import service.fund.FundAnalyzer
import service.fund.FundCrawl
import service.fund.FundSrv

@Field final Logger log = LoggerFactory.getLogger(getClass())
@Field final AppContext app = new AppContext()


// 系统功能添加区
app.addSource(new EhcacheSrv())
app.addSource(new SchedSrv())
//ctx.addSource(new RedisClient())
app.addSource(new OkHttpSrv())
app.addSource(new Remoter())
app.addSource(new HibernateSrv().entities(Fund, FundHistory))
app.addSource(new RatpackWeb().ctrls(MainCtrl, FundCtrl))
app.addSource(new FundSrv())
app.addSource(new FundCrawl())
app.addSource(new FundAnalyzer())
app.addSource(new TestService())
app.addSource(this)
app.start() // 启动系统


@EL(name = 'sys.started')
def sysStarted() {
    try {

    } finally {
        // System.exit(0)
        // ep.fire('sched.after', EC.of(this).args(Duration.ofSeconds(5), {System.exit(0)}).completeFn({ec -> if (ec.noListener) System.exit(0) }))
    }
}