package service.fund

import cn.xnatural.enet.event.EL
import com.alibaba.fastjson.JSONObject
import core.module.OkHttpSrv
import core.module.SchedSrv
import core.module.ServerTpl
import core.module.jpa.BaseRepo
import dao.entity.fund.Fund

class FundSrv extends ServerTpl {

    @Lazy def http = bean(OkHttpSrv)
    @Lazy def sched = bean(SchedSrv)
    @Lazy def repo = bean(BaseRepo)
    @Lazy def analyzer = bean(FundAnalyzer)
    @Lazy def crawl = bean(FundCrawl)
    @Lazy String ddMsgUrl = getStr('ddMsgUrl', "https://oapi.dingtalk.com/robot/send?access_token=7e9d8d97e6b5e76a6a07b0c5d7c31e82f0fbdb8ced1ac23168f9fd5c28c57f1f")
    @Lazy String ddMsgSecureKey = getStr('ddMsgSecureKey', "https://oapi.dingtalk.com/robot/send?access_token=7e9d8d97e6b5e76a6a07b0c5d7c31e82f0fbdb8ced1ac23168f9fd5c28c57f1f")


    @EL(name = 'sys.started', async = true)
    void start() {
        sched.cron("0 40,50 14 ? * 1,2,3,4,5 *") {ddMsg("JJ提醒")}
        // sched.cron("0 10 01 3,13,23 * ?", {crawl.addTask{crawl.updateFunds()}})

        boolean workday = {
           def cal = Calendar.getInstance()
            int week = cal.get(Calendar.DAY_OF_WEEK)
            week == 1 || week == 7 ? false : true
        }()

        if (workday) {
            sched.cron("0 40 9 * * ?", {
                crawl.queue {  updateByType('股票指数') }
                // crawl.addTask { updateByType('混合型') }
            })
            def updatePriceFn = { // 更新最新价格信息
                crawl.queue {
                    for (int page = 1; ; page++) {
                        def p = repo.findPage(Fund, page, 100, { root, query, cb -> {
                            root.get('type').in('股票指数')
                        }})
                        p.list.each{ crawl.updateNewestPrice(it.code); analyzer.analyze(it.code); Thread.sleep(100L) }
                        if (p.page >= p.totalPage) break
                    }
                    log.info("更新最新价格信息 完成")
                }
            }
            sched.cron("0 15,28,40,50,55 10,11,12,13,14 * * ?", updatePriceFn)
            sched.cron("0 4 15 * * ?", updatePriceFn)
            sched.cron("0 30 13 * * ?", { crawl.queue{updateByType('货币型')} })
//        sched.cron("0 40 15 * * ?", {
//            crawl.addTask { updateByType('混合型') }
//            crawl.addTask { updateByType('股票型') }
//        })
        }
    }


    /**
     * 发送钉钉消息
     * @param msg
     * @return
     */
    void ddMsg(String msg) {
        JSONObject msgJo = new JSONObject(3)
        msgJo.put("msgtype", "text")
        msgJo.put("text", new JSONObject(1).fluentPut("content", "Fund: " + msg))
        msgJo.put("at", new JSONObject(1).fluentPut("isAtAll", true))
        http.post(ddMsgUrl).jsonBody(msgJo.toString()).execute()
    }


    /**
     * 更新某个类别
     * @param type
     */
    void updateByType(String type) {
        String msg = "更新类别: $type"
        log.info(msg)
        ep.fire("wsMsg", msg)

        for (int page = 1; ; page++) {
            def p = repo.findPage(Fund, page, 100, { root, query, cb -> {
                def ps = []
                ps << cb.or(cb.equal(root.get('available'), true), cb.isNull(root.get('available')))
                if (type == null) ps << cb.isNull(root.get('type'))
                else if (type == '') ps << cb.equal(root.get('type'), '')
                else ps << cb.equal(root.get('type'), type)
                ps.stream().reduce({p1, p2 -> cb.and(p1, p2)}).orElseGet{null}
            }})
            p.list.each {crawl.updateFund(it.code)}
            if (p.page >= p.totalPage) break
        }

        msg = "更新类别结束: $type"
        log.info(msg)
        ep.fire("wsMsg", msg)
    }
}
