package ctrl

import cn.xnatural.enet.event.EL
import core.AppContext
import core.Page
import core.Utils
import core.module.jpa.BaseRepo
import ctrl.common.ApiResp
import dao.entity.fund.Fund
import dao.entity.fund.FundHistory
import ratpack.form.Form
import ratpack.handling.Chain
import ratpack.websocket.*
import service.fund.FundAnalyzer
import service.fund.FundCrawl
import service.fund.FundSrv

import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap

class FundCtrl extends CtrlTpl {

    @Lazy def            repo     = bean(BaseRepo)
    @Lazy def            fundSrv  = bean(FundSrv)
    @Lazy def            crawl    = bean(FundCrawl)
    final Set<WebSocket> wss      = ConcurrentHashMap.newKeySet()

    FundCtrl() {prefix = 'fund'}


    @EL(name = 'wsMsg')
    void wsMsg(Object msg) { // Object 是为了兼容 GString
        if (msg != null) wss.each {ws -> ws.send(msg.toString())}
    }


    void fundIndexPage(Chain chain) {
        chain.get('fund.html') { ctx -> ctx.render ctx.file('static/fund/fund.html') }
    }


    // 执行前端传过来脚本代码
    void cusScript(Chain chain) {
        chain.post('cusScript') {ctx ->
            string(ctx) {body, fn ->
                Utils.eval(body, [ctx: bean(AppContext), crawl: crawl, analyzer: bean(FundAnalyzer), repo: repo, fundSrv: fundSrv])
                fn.accept(ApiResp.ok().desc('脚本执行成功'))
            }
        }
    }


    void info(Chain chain) {
        chain.get('info') {ctx ->
            get(ctx) {params, fn ->
                fn.accept(ApiResp.ok(
                    Page<Map>.of(
                        repo.findById(Fund, params['code']),
                        { Fund e ->
                            Utils.toMapper(e)
                                .addConverter('start', {v -> new SimpleDateFormat('yyyy-MM-dd').format(v)})
                                .addConverter('createTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                                .addConverter('updateTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                                .build()
                        }
                    )
                ))
            }

        }
    }


    // 得到所有类型
    void types(Chain chain) {
        chain.get('types') {ctx ->
            get(ctx) {params, fn ->
                fn.accept(ApiResp.ok(
                    repo.trans{s -> s.createQuery("select distinct type from Fund group by type").list()}
                ))
            }
        }
    }


    // 根据基金代码手动更新基金
    void updateFund(Chain chain) {
        chain.post('updateFund') {ctx ->
            form(ctx) {fd, fn ->
                String code = fd['code']
                String type = fd['type']
                if (!code && !type) throw new IllegalArgumentException("code 和 type 参数至少传一个")
                if (code) {
                    crawl.updateFund(code, true)

                    // 返回更新后的数据
                    fn.accept ApiResp.ok(
                        Utils.toMapper(repo.findById(Fund, code))
                            .addConverter('start', {v -> new SimpleDateFormat('yyyy-MM-dd').format(v)})
                            .addConverter('createTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                            .addConverter('updateTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                            .build()
                    )
                }
                if (type) {
                    if (type == '--') {
                        crawl.queue() { fundSrv.updateByType(null) }
                        crawl.queue() { fundSrv.updateByType('') }
                    }
                    else crawl.queue() { fundSrv.updateByType(type) }
                    fn.accept(ApiResp.ok().desc("等待更新类型('$type')完成"))
                }
            }
        }
    }


    // 基金列表
    void fundList(Chain chain) {
        chain.get('list') {ctx ->
            get(ctx) {params, fn ->
                Integer page = Integer.valueOf(params.getOrDefault('page', 1))
                String code = params['code']
                String type = params['type']
                String historyLowest = params['historyLowest']
                String name = params['name']
                String desc = params['desc']
                String asc = params['asc']
                String descFirst = params.getOrDefault('descFirst', 'true')
                String available = params['available']
                fn.accept(ApiResp.ok(
                    Page<Map>.of(
                        repo.findPage(Fund, page, 15, { root, query, cb ->
                            def os = []
                            if (Boolean.TRUE == Boolean.valueOf(descFirst)) {
                                if (desc) {
                                    desc.split(",").each {
                                        if (it && it.trim()) os << cb.desc(root.get(it.trim()))
                                    }
                                }
                                if (asc) {
                                    asc.split(",").each {
                                        if (it && it.trim()) os << cb.asc(root.get(it.trim()))
                                    }
                                }
                            }
                            else {
                                if (asc) {
                                    asc.split(",").each {
                                        if (it && it.trim()) os << cb.asc(root.get(it.trim()))
                                    }
                                }
                                if (desc) {
                                    desc.split(",").each {
                                        if (it && it.trim()) os << cb.desc(root.get(it.trim()))
                                    }
                                }
                            }

                            if (os) query.orderBy(os)
                            else query.orderBy(cb.desc(root.get('total'))) //默认按total排序
                            def ps = []
                            if (name) {ps << cb.like(root.get('name'), '%' + name +'%')}
                            if (code) {ps << cb.like(root.get('code'), '%' + code + '%')}
                            if (type) {
                                def ps1 = []
                                type.split(",").each {t ->
                                    if ('--' == type) {
                                        ps1 << cb.isNull(root.get('type'))
                                        ps1 << cb.equal(root.get('type'), '')
                                    } else ps1 << cb.equal(root.get('type'), t)
                                }
                                ps1.stream().reduce({p1, p2 -> cb.or(p1, p2)}).ifPresent{ps << it}
                            }
                            if (historyLowest != null && !historyLowest.trim().isEmpty()) {ps << cb.equal(root.get('historyLowest'), Boolean.valueOf(historyLowest))}
                            if (available) {ps << cb.equal(root.get('available'), Boolean.valueOf(available))}
                            ps.stream().reduce({p1, p2 -> cb.and(p1, p2)}).orElseGet{null}
                        }),
                        { Fund e ->
                            Utils.toMapper(e)
                                .addConverter('start', {v -> new SimpleDateFormat('yyyy-MM-dd').format(v)})
                                .addConverter('createTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                                .addConverter('updateTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                                .build()
                        }
                    )
                ))
            }
        }
    }


    // 基金历史数据
    void histories(Chain chain) {
        chain.get('histories') {ctx ->
            get(ctx) {params, fn->
                Integer page = Integer.valueOf(params['page']?:1)
                Integer pageSize = Integer.valueOf(params['pageSize']?:15)
                String code = params['code']
                String desc = params['desc']
                String asc = params['asc']
                String startTime = params['startTime']
                String endTime = params['endTime']
                fn.accept(ApiResp.ok(
                    Page<Map>.of(
                        repo.findPage(FundHistory, page, pageSize, { root, query, cb ->
                            def os = []
                            if (desc) os << cb.desc(root.get(desc))
                            if (asc) os << cb.asc(root.get(asc))
                            if (os) query.orderBy(os)
                            else query.orderBy(cb.desc(root.get('date'))) //默认按时间排序

                            def ps = []
                            if (code) {ps << cb.equal(root.get('code'), code)}
                            if (startTime) {ps << cb.greaterThanOrEqualTo(root.get('date'), new Date(Long.valueOf(startTime)))}
                            if (endTime) {ps << cb.lessThanOrEqualTo(root.get('date'), new Date(Long.valueOf(endTime)))}
                            ps.stream().reduce({p1, p2 -> cb.and(p1, p2)}).orElseGet{null}
                        }),
                        { FundHistory e ->
                            Utils.toMapper(e)
                                .addConverter('date', {v -> new SimpleDateFormat('yyyy-MM-dd').format(v)})
                                .addConverter('createTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                                .addConverter('updateTime', {v -> new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(v)})
                                .build()
                        }
                    )
                ))
            }
        }
    }


    // web socket
    void ws(Chain chain) {
        chain.get('ws') {ctx ->
            WebSockets.websocket(ctx, new WebSocketHandler<WebSocket>() {
                @Override
                WebSocket onOpen(WebSocket ws) throws Exception {
                    wss.add(ws)
                    log.info('fund ws connect. {}', ctx.request.remoteAddress)
                    return ws
                }

                @Override
                void onClose(WebSocketClose<WebSocket> close) throws Exception {
                    wss.remove(close.getOpenResult())
                    log.info('fund ws closed. {}. fromClient: ' + close.fromClient + ', fromServer: ' + close.fromServer, ctx.request.remoteAddress)
                }

                @Override
                void onMessage(WebSocketMessage<WebSocket> frame) throws Exception {
                    log.info('fund ws receive client msg: {}', frame.text)
                }
            })
        }
    }
}
