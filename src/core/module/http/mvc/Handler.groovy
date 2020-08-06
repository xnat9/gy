package core.module.http.mvc

import core.module.http.HttpContext

abstract class Handler {


    /**
     * 逻辑处理
     * @param ctx
     */
    abstract void handle(HttpContext ctx)

    /**
     * 匹配的顺序, 越大越先匹配
     * @return
     */
    float order() {0}


    /**
     * 匹配
     * @param ctx
     * @return
     */
    boolean match(HttpContext ctx) {false}



    /**
     * 去掉 路径 前后 的 /
     * @param path
     * @return
     */
    static String extract(String path) {
        if (path.endsWith("/")) path = path.substring(0, path.length() - 2)
        if (path.startsWith("/")) path = path.substring(1)
        return path
    }
}
