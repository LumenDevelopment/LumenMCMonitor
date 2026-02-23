package cloud.lumenvm.monitor;

import cloud.lumenvm.api.AddonCommand;
import cloud.lumenvm.api.CommandType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class CommandRegistry {

    // TODO Comments

    public final Map<String, AddonCommand> commands = new HashMap<>();
    public final Map<String, AddonCommand> webhookCommands = new HashMap<>();
    public final Map<String, AddonCommand> monitorCommands = new HashMap<>();

    public void register(AddonCommand addonCommand) {
        int i = 1;
        while(true) {
            if (!commands.containsKey(addonCommand.name().toLowerCase() + i)) {
                commands.put(addonCommand.name() + i, addonCommand);
                break;
            }
            i++;
        }
        if (addonCommand.type() == CommandType.MONITOR) {
            monitorCommands.put(addonCommand.name(), addonCommand);
        } else if (addonCommand.type() == CommandType.WEBHOOK) {
            webhookCommands.put(addonCommand.name(), addonCommand);
        }
    }

    public AddonCommand get(String name) {
        return commands.get(name);
    }

    public AddonCommand getFromMonitor(String name) {
        return monitorCommands.get(name);
    }

    public AddonCommand getFromWebhook(String name) {
        return webhookCommands.get(name);
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