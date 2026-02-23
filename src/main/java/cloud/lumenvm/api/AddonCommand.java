package cloud.lumenvm.api;

import org.bukkit.command.CommandSender;

import java.util.List;

/**Used to register commands within addons*/
public interface AddonCommand {

    /**@return name of the command (/lumenmc|webhook addon [name])*/
    String name();

    /**@return type of the command ({@link CommandType#MONITOR} or {@link CommandType#WEBHOOK})*/
    CommandType type();

    /**
     * This is like onCommand in a normal plugin.
     * <br>
     * Executes the given command, returning its success.
     * <br>
     * If false is returned, then the "usage" plugin.yml entry for this command
     * (if defined) will be sent to the player.
     * @param sender Source of the command
     * @param args Passed command arguments
     * @return true if a valid command, otherwise false
     * */
    default boolean execute(CommandSender sender, String[] args) {
        return true;
    }

    /**
     * This is like onTabComplete in a normal plugin.
     * <br>
     * Requests a list of possible completions for a command argument.
     * @param sender Source of the command.  For players tab-completing a
     * command inside of a command block, this will be the player, not
     * the command block.
     * @param args The arguments passed to the command, including final
     * partial argument to be completed
     * @return A List of possible completions for the final argument, or null
     * to default to the command executor
     * */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

}
