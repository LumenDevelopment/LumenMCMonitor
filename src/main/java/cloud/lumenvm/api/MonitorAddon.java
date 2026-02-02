package cloud.lumenvm.api;

public interface MonitorAddon {

    String getName();

    void onLoad(MonitorAPI api);

    void onUnload();

}
