package net.canarymod.commandsys.commands;

import net.canarymod.Canary;
import net.canarymod.Translator;
import net.canarymod.api.Server;
import net.canarymod.api.entity.living.humanoid.Player;
import net.canarymod.chat.Colors;
import net.canarymod.chat.MessageReceiver;
import net.canarymod.commandsys.CommandException;
import net.canarymod.hook.player.TeleportHook;

public class TeleportCommand {

    public void execute(MessageReceiver caller, String[] parameters) {
        if (caller instanceof Server) {
            console((Server) caller, parameters);
        } else if (caller instanceof Player) {
            player((Player) caller, parameters);
        } else {
            throw new CommandException("Unknown MessageReceiver: " + caller.getClass().getSimpleName());
        }
    }

    private void console(Server caller, String[] args) {
        caller.notice(Translator.translate("tp console"));
    }

    private void player(Player player, String[] args) {
        Player target = Canary.getServer().matchPlayer(args[1]);

        if (target != null) {
            player.teleportTo(target.getLocation(), TeleportHook.TeleportCause.COMMAND);
            player.sendMessage(Colors.YELLOW + Translator.translateAndFormat("tp success", target.getName()));
        } else {
            player.notice(Translator.translateAndFormat("unknown player", args[1]));
        }
    }

}
