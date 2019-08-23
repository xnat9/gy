package sevice

import cn.xnatural.enet.event.EL
import cn.xnatural.enet.server.ServerTpl
import org.apache.commons.io.IOUtils
import rest.FileData

import java.util.concurrent.Executors

class FileUploader extends ServerTpl {
    /**
     * 文件上传的 本地保存目录
     */
    private String localDir;
    /**
     * 文件上传 的访问url前缀
     */
    private URI    urlPrefix;


    FileUploader() { super("file-uploader"); }


    @EL(name = "sys.starting")
    protected void init() {
        attrs.putAll((Map<? extends String, ?>) ep.fire("env.ns", getName()));
        try {
            localDir = getStr("local-dir", new URL("file:upload").getFile());
            File dir = new File(localDir); dir.mkdirs();
            log.info("save upload file local dir: {}", dir.getAbsolutePath());

            urlPrefix = URI.create(getStr("url-prefix", ("http://" + ep.fire("http.getHp") + "/file/")) + "/").normalize();
            log.info("access upload file url prefix: {}", urlPrefix);
        } catch (MalformedURLException e) {
            log.error(e);
        }
    }


    /**
     *  例: 文件名为 aa.txt, 返回: arr[0]=aa, arr[1]=txt
     * @param fileName
     * @return
     */
    String[] extractFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return [null, null];
        int i = fileName.lastIndexOf(".");
        if (i == -1) return [fileName, null];
        return [fileName.substring(0, i), fileName.substring(i + 1)];
    }


    /**
     * 映射 文件名 到一个 url
     * @param fileName
     * @return
     */
    String toFullUrl(String fileName) {
        return urlPrefix.resolve(fileName).toString();
    }


    /**
     * 查找文件
     * @param fileName
     * @return
     */
    File findFile(String fileName) {
        return new File(localDir + File.separator + fileName);
    }


    @EL(name = "deleteFile")
    void delete(String fileName) {
        File f = new File(localDir + File.separator + fileName);
        if (f.exists()) f.delete();
        else log.warn("delete file '${fileName}' not exists");
    }


    /**
     * 多文件 多线程保存
     * @param files
     */
    // @Monitor(warnTimeOut = 7000)
    void save(FileData... files) {
        if (files == null || files.length == 0) return;

        // 文件流copy
        def doSave = {FileData fd ->
            if (fd == null) return;
            IOUtils.copy(fd.inputStream, new FileOutputStream(new File(localDir + File.separator + f.getResultName())));
            return fd
        }

        // 并发上传
        if (files.length >= 2) {
            def execs = Executors.newFixedThreadPool(files.size() - 1)
            def fs = files.drop(1).collect {fd -> execs.submit(doSave(fd))}.collect()
            doSave(files[0])
            fs.each {f -> f.get()}
        } else if (files.length == 1){ doSave(files[0]) }
    }


    String getLocalDir() {localDir}
}
