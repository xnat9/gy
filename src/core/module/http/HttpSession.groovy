package core.module.http

import core.Devourer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.ClosedChannelException
import java.nio.channels.CompletionHandler
import java.util.concurrent.ExecutorService

class HttpSession {
    protected static final Logger                               log         = LoggerFactory.getLogger(HttpSession)
    protected final        AsynchronousSocketChannel            sc
    protected final                                             readHandler = new ReadHandler(this)
    protected final                                             buf         = ByteBuffer.allocate(1024 * 10)
    protected final        ExecutorService                      exec
    // close 回调函数
    protected              Runnable                             closeFn


    HttpSession(AsynchronousSocketChannel sc, ExecutorService exec) {
        assert sc != null: "sc must not be null"
        assert exec != null: "exec must not be null"
        this.sc = sc
        this.exec = exec
    }


    /**
     * 开始数据接收处理
     */
    void start() { read() }


    /**
     * 关闭
     */
    void close() {sc?.close(); closeFn?.run()}


    /**
     * 发送消息到客户端
     * @param msg
     */
    void send(String msg) {
        if (msg == null) return
        try {
            sc.write(ByteBuffer.wrap(msg.getBytes('utf-8'))).get()
        } catch (ClosedChannelException ex) {
            log.error("ClosedChannelException " + sc.localAddress.toString() + " ->" + sc.remoteAddress.toString())
            close()
        }
    }


    /**
     * 继续处理接收数据
     */
    protected void read() {
        buf.clear()
        sc.read(buf, buf, readHandler)
    }


    protected class ReadHandler implements CompletionHandler<Integer, ByteBuffer> {
        final HttpSession session

        ReadHandler(HttpSession session) { assert session != null; this.session = session }

        @Override
        void completed(Integer count, ByteBuffer buf) {
            if (count > 0) {
                buf.flip()
                byte[] bs = new byte[buf.limit()]
                buf.get(bs)
                // 避免 ReadPendingException
                session.read()
            }
            else {
                log.warn(session.sc.remoteAddress.toString() + "接收字节为空. 关闭")
                session.close()
            }
        }


        @Override
        void failed(Throwable ex, ByteBuffer buf) {
            if (ex instanceof ClosedChannelException) session.close()
            log.error(ex.message?:ex.class.simpleName, ex)
        }
    }
}
