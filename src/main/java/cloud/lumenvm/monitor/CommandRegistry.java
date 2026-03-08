package cloud.lumenvm.monitor;

import cloud.lumenvm.api.AddonCommand;
import cloud.lumenvm.api.CommandType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// Addons may use this to add their commands
public class CommandRegistry {

    // All commands
    public final Map<String, AddonCommand> commands = new HashMap<>();

    // /webhook commands
    public final Map<String, AddonCommand> webhookCommands = new HashMap<>();

    // /lumenmc commands
    public final Map<String, AddonCommand> monitorCommands = new HashMap<>();

    // Addons pass AddonCommand interface that shapes the command
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

    // Gets commands by name
    public AddonCommand get(String name) {
        return commands.get(name);
    }

    // Gets /lumenmc commands by name
    public AddonCommand getFromMonitor(String name) {
        return monitorCommands.get(name);
    }

    // Gets /webhook commands by name
    public AddonCommand getFromWebhook(String name) {
        return webhookCommands.get(name);
    }

    // Gets Collection of all commands
    public Collection<AddonCommand> getAll() {
        return commands.values();
    }

    // Gets all /lumenmc commands
    public Collection<AddonCommand> getAllMonitor() {
        return monitorCommands.values();
    }

    // Gets all /webhook commands
    public Collection<AddonCommand> getAllWebhook() {
        return webhookCommands.values();
    }

}