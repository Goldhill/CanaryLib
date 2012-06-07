package net.canarymod.user;

import java.util.ArrayList;
import java.util.HashMap;

import net.canarymod.api.entity.Player;
import net.canarymod.backbone.BackboneGroups;
import net.canarymod.backbone.BackboneUsers;
import net.canarymod.config.Configuration;
import net.canarymod.database.Database;
import net.canarymod.permissionsystem.PermissionManager;

public class UserAndGroupsProvider {
    private ArrayList<Group> groups;
    private HashMap<String,String[]> playerData;
    private BackboneGroups backboneGroups;
    private BackboneUsers backboneUsers;
    private Group defaultGroup;

    /**
     * Instantiate a groups provider
     * 
     * @param bone
     * @param type
     */
    public UserAndGroupsProvider(Database database) {
        backboneGroups = new BackboneGroups(database, Configuration.getServerConfig().getDatasourceType());
        backboneUsers = new BackboneUsers(database, Configuration.getServerConfig().getDatasourceType());
        groups = backboneGroups.loadGroups();
        playerData = backboneUsers.loadUsers();
        //Add permission sets to groups
        ArrayList<Group> groups = new ArrayList<Group>();
        for(Group g : this.groups) {
            g.permissions = new PermissionManager().getGroupsProvider(g.name); //Need to do this here because Canary isn't ready at this time
            groups.add(g);
        }
        this.groups = groups;
        
        //find default group
        for(Group g : groups) {
            if(g.defaultGroup) {
                defaultGroup = g;
                break;
            }
        }
        if(defaultGroup == null) {
            throw new IllegalStateException("No default group defined! Please define a default group!");
        }
    }

    /**
     * Add a new Group
     * 
     * @param g
     */
    public void addGroup(Group g) {
        if(groupExists(g.name)) {
            backboneGroups.updateGroup(g);
        }
        else {
            backboneGroups.addGroup(g);
        }
        groups.add(g);
    }

    /**
     * Remove this group
     * 
     * @param g
     */
    public void removeGroup(Group g) {
        backboneGroups.removeGroup(g);
        groups.remove(g);
    }

    /**
     * Check if a group by the given name exists
     * 
     * @param name
     * @return
     */
    public boolean groupExists(String name) {
        for (Group g : groups) {
            if (g.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the given group is filed in this groups provider
     * 
     * @param g
     * @return
     */
    public boolean groupExists(Group g) {
        return groups.contains(g);
    }

    /**
     * Return array of all existent groups
     * 
     * @return
     */
    public Group[] getGroups() {
        return (Group[]) groups.toArray();
    }

    /**
     * Returns group files under the given name or the default group if the specified one doesn't exist
     * 
     * @param name
     * @return
     */
    public Group getGroup(String name) {
        if(name == null || name.isEmpty()) {
            return defaultGroup;
        }
        for (Group g : groups) {
            if (g.name.equals(name)) {
                return g;
            }
        }
        return defaultGroup;
    }
    
    public Group getDefaultGroup() {
        return this.defaultGroup;
    }
    
    /**
     * Returns a String array containing data in this order:
     * Prefix, Group, IP list (comma seperated)
     * @param name
     * @return
     */
    public String[] getPlayerData(String name) {
        String[] data = playerData.get(name);
        if(data == null) {
            data = new String[3];
            data[0] = null;
            data[1] = defaultGroup.name;
            data[2] = null;
        }
        return data;
    }
    
    /**
     * Add or update the given player
     * @param player
     */
    public void addOrUpdatePlayerData(Player player) {
        backboneUsers.addUser(player);
        String[] content = new String[3];
        content[0] = player.getColor();
        content[1] = player.getGroup().name;
        StringBuilder ips = new StringBuilder();
        for(String ip : player.getAllowedIPs()) {
            ips.append(ip).append(",");
        }
        ips.deleteCharAt(ips.length()-1); //remove last comma
        content[2] = ips.toString();
        playerData.put(player.getName(), content);
    }
    
    /**
     * Remove permissions and other data for this player from database
     * @param player
     */
    public void removeUserData(Player player) {
        backboneUsers.removeUser(player);
    }
}
