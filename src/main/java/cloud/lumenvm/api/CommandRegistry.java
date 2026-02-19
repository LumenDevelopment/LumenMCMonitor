package cloud.lumenvm.api;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class CommandRegistry {

    private final Map<String, AddonCommand> commands = new HashMap<>();
    private final Map<String, AddonCommand> webhookCommands = new HashMap<>();
    private final Map<String, AddonCommand> monitorCommands = new HashMap<>();

    public void register(AddonCommand addonCommand) {
        commands.put(addonCommand.name().toLowerCase(), addonCommand);
        if (addonCommand.type() == CommandType.MONITOR) {
            monitorCommands.put(addonCommand.name(), addonCommand);
        } else if (addonCommand.type() == CommandType.WEBHOOK) {
            webhookCommands.put(addonCommand.name(), addonCommand);
        }
    }

    public AddonCommand get(String name) {
        return commands.get(name);
    }

    public Collection<AddonCommand> getAll() {
        return commands.values();
    }

    public Collection<AddonCommand> getAllMonitor() {
        return monitorCommands.values();
    }

    public Collection<AddonCommand> getAllWebhook() {
        return webhookCommands.values();
    }

}