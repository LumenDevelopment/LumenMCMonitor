package cloud.lumenvm.api;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface AddonCommand {

    String name();

    CommandType type();

    boolean execute(CommandSender sender, String[] args);

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

}
