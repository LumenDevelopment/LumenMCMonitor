package cloud.lumenvm.api;

/**Two command types:
 * {@link #MONITOR}
 * and
 * {@link #WEBHOOK}*/
public enum CommandType {
    /**When used, command will be in /<strong>lumenmc</strong> addon*/
    MONITOR,
    /**When used, command will be in /<strong>webhook</strong> addon*/
    WEBHOOK
}
