package sevice.rule

import cn.xnatural.enet.event.EL
import core.module.ServerTpl
import groovy.transform.ToString

import java.util.function.Function

class RuleSev extends ServerTpl {


    @EL(name = 'sys.starting')
    def start() {
        return
//        rule {
//            id = 'r1'
//            name = "测试$id"
//
//            condition {
//                gt('age', 18) and lt('age', 60)
//                or {
//                    prefix('name', 'x') or suffix('name', 'n')
//                }
//            }
//            process = {true}
//            trueNext {
//                id = 'r2'
//                name = "测试$id"
//
//            }
//
//            falseNext {
//
//            }
//        } run(new RuleContext([name: 'xxb', 'age': 18]))
    }


    RuleChain rule(@DelegatesTo(RuleChain) Closure cl) {
        RuleChain rule = new RuleChain()
        def code = cl.rehydrate(rule, rule, rule)
        cl.resolveStrategy = Closure.DELEGATE_ONLY
        code()
    }


    class RuleChain {
                String                         id
                String                         name
        private ConditionChain                 condition
        private Function<RuleContext, Boolean> process
        private RuleChain                      trueNext
        private RuleChain                      falseNext

        RuleChain condition(@DelegatesTo(ConditionChain) Closure cl) {
            condition = new ConditionChain()
            def code = cl.rehydrate(condition, condition, condition)
            // cl.resolveStrategy = Closure.DELEGATE_ONLY
            code()
            this
        }

        RuleChain process(Function<RuleContext, Boolean> fn) {
            this.process = fn; this
        }

        RuleChain trueNext(@DelegatesTo(RuleChain) Closure cl) {
            trueNext = new RuleChain()
            def code = cl.rehydrate(trueNext, trueNext, trueNext)
            // cl.resolveStrategy = Closure.DELEGATE_ONLY
            code()
            trueNext
        }

        RuleChain falseNext(@DelegatesTo(RuleChain) Closure cl) {
            falseNext = new RuleChain()
            def code = cl.rehydrate(falseNext, falseNext, falseNext)
            // cl.resolveStrategy = Closure.DELEGATE_ONLY
            code()
            falseNext
        }

        boolean run(RuleContext ctx) {
            if (condition?.eval(ctx)) {
                def f = process?.apply(ctx)
                if (trueNext == null || !f) return f
                return trueNext.run(ctx)
            } else {
                return falseNext?.run(ctx)
            }
        }
    }


    class RuleContext {
        final Map data = new HashMap()

        RuleContext() {}

        RuleContext(Map m) { this.data.putAll(m) }

        RuleContext attr(String k, Object v) {data.put(k, v); this}

        def get(String pName) {
            if (data.containsKey(pName)) return data[(pName)]
            // TODO 属性值获取
        }
//        String getStr(String pName) {
//            if (attr.containsKey(pName)) return attr[(pName)]
//            return null
//        }
//
//        Boolean getBoolean(String pName) {
//            if (attr.containsKey(pName)) return Boolean.valueOf(attr[(pName)])
//            return null
//        }
//
//        Integer getInteger(String pName) {
//            if (attr.containsKey(pName)) return Integer.valueOf(attr[(pName)])
//            return null
//        }
//
//        Double getDouble(String pName) {
//            if (attr.containsKey(pName)) return Double.valueOf(attr[(pName)])
//            return null
//        }
    }


    /**
     * 条件链
     */
    @ToString
    class ConditionChain {
        private String         property
        private String         op
        private Object         value
        private ConditionChain next
        private String         nextOp

        ConditionChain and(@DelegatesTo(ConditionChain) Closure cl) {
            next = new ConditionChain()
            def code = cl.rehydrate(next, this, this)
            cl.resolveStrategy = Closure.DELEGATE_ONLY
            code()
            nextOp = "and"
            return next
        }

        ConditionChain or(@DelegatesTo(ConditionChain) Closure cl) {
            next = new ConditionChain()
            def code = cl.rehydrate(next, this, this)
            cl.resolveStrategy = Closure.DELEGATE_ONLY
            code()
            nextOp = "or"
            return next
        }

        boolean eval(RuleContext ctx) {
            def v = ctx.get(property)
            if (v == null) return false // 不存在默认返回false

            // 计算
            boolean f
            try {
                if ("==" == op) f = (v == value)
                else if (">" == op) f = (((Number) v) <=> (Number) value) > 0 ? true : false
                else if ("<" == op) f = (((Number) v) <=> (Number) value) > 0 ? true : false
                else if (">=" == op) f = (((Number) v) <=> (Number) value) > 0 ? true : false
                else if ("<=" == op) f = (((Number) v) <=> (Number) value) > 0 ? true : false
                else if ("contains" == op) f = ((String) v).contains(v)
                else if ("prefix" == op) f = ((String) v).startsWith(v)
                else if ("suffix" == op) f = ((String) v).endsWith(v)
            } catch (Exception ex) {
                throw new RuntimeException("'$property':$v $op $value", ex)
            }

            if (next != null) {
                if (f && 'and' == nextOp) f = next.eval(ctx)
                else if (!f && 'or' == nextOp) f = next.eval(ctx)
            }
            return f
        }

        ConditionChain eq(String field, Object value) {
            new ConditionChain(property: field, op: "==", value: value)
        }

        ConditionChain gt(String field, Object value) {
            new ConditionChain(property: field, op: ">", value: value)
        }

        ConditionChain ge(String field, Object value) {
            new ConditionChain(property: field, op: ">=", value: value)
        }

        ConditionChain lt(String field, Object value) {
            new ConditionChain(property: field, op: "<", value: value)
        }

        ConditionChain le(String field, Object value) {
            new ConditionChain(property: field, op: "<=", value: value)
        }

        ConditionChain contains(String field, Object value) {
            new ConditionChain(property: field, op: "contains", value: value)
        }

        ConditionChain prefix(String field, Object value) {
            new ConditionChain(property: field, op: "prefix", value: value)
        }

        ConditionChain suffix(String field, Object value) {
            new ConditionChain(property: field, op: "suffix", value: value)
        }
    }

}
