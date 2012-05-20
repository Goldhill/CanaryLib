package net.canarymod.plugin;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import net.canarymod.LogManager;
import net.canarymod.config.ConfigurationFile;

/**
 * This class loads, reload, enables and disables plugins.
 * 
 * @author Jos Kuijpers
 */
public class PluginLoader {

    private static final Object lock = new Object();

    // Loaded plugins
    private List<Plugin> plugins;

    // Plugins that will be loaded before the world
    private HashMap<String, URLClassLoader> preLoad;
    // Dependency storage for the pre-load plugins
    private HashMap<String, ArrayList<String>> preLoadDependencies;
    // Solved order to load preload plugins
    private ArrayList<String> preOrder;

    // Plugins that will be loaded after the world
    private HashMap<String, URLClassLoader> postLoad;
    // Dependency storage for the post-load plugins
    private HashMap<String, ArrayList<String>> postLoadDependencies;
    // Solved order to load postload plugins
    private ArrayList<String> postOrder;

    // Plugin names that won't be loaded
    private ArrayList<String> noLoad;

    private HashMap<String, String> casedNames;

    private int stage = 0; // 0 none, 1 scanned, 2 pre, 3 pre+post

    public PluginLoader() {
        this.plugins = new ArrayList<Plugin>();
        this.preLoad = new HashMap<String, URLClassLoader>();
        this.postLoad = new HashMap<String, URLClassLoader>();
        this.noLoad = new ArrayList<String>();
        this.preLoadDependencies = new HashMap<String, ArrayList<String>>();
        this.postLoadDependencies = new HashMap<String, ArrayList<String>>();
        this.casedNames = new HashMap<String, String>();
    }

    /**
     * Scan for plugins: find the plugins and examine them. Then solve the
     * dependency lists
     * 
     * @return
     */
    public boolean scanPlugins() {
        // We can't do a rescan this way because it needs a reload 
        // of the plugins (AFAIK)
        if (stage != 0) return false;

        File dir = new File("plugins/");
        if (!dir.isDirectory()) {
            LogManager.get().logSevere("Failed to scan for plugins. 'plugins/' is not a directory.");
            return false;
        }

        for (String classes : dir.list()) {
            if (!classes.endsWith(".jar")) continue;
            if (!this.scan(classes)) continue;
            String sname = classes.toLowerCase();
            this.casedNames.put(sname.substring(0, sname.lastIndexOf(".")), classes);
        }

        // Solve the dependency tree

        preOrder = this.solveDependencies(this.preLoadDependencies);
        if (preOrder == null) {
            LogManager.get().logSevere("Failed to solve preload dependency list.");
            return false;
        }

        postOrder = this.solveDependencies(this.postLoadDependencies);
        if (postOrder == null) {
            LogManager.get().logSevere("Failed to solve postload dependency list.");
            return false;
        }

        // Change the stage
        stage = 1;

        return true;
    }

    /**
     * Loads the plugins for pre or post load
     * 
     * @param preLoad
     */
    public boolean loadPlugins(boolean preLoad) {
        if ((preLoad && stage != 1) || stage == 3) return false;
        LogManager.get().logInfo("Loading " + ((preLoad) ? "preloadable " : "") + "plugins...");
        if (preLoad) {
            for (String name : this.preOrder) {
                String rname = this.casedNames.get(name);
                this.load(rname + ".jar", this.preLoad.get(name));
            }
            this.preLoad.clear();
        } else {
            for (String name : this.postOrder) {
                String rname = this.casedNames.get(name);
                this.load(rname + ".jar", this.postLoad.get(name));
            }
            this.postLoad.clear();
        }

        LogManager.get().logInfo("Loaded " + ((preLoad) ? "preloadable " : "") + "plugins...");

        // Prevent a double-load (which makes the server crash)
        stage++;

        return true;
    }

    /**
     * Extract information from the given Jar
     * 
     * This information includes the dependencies and mount point
     * 
     * @param filename
     * @return
     */
    private boolean scan(String filename) {
        try {
            File file = new File("plugins/" + filename);
            String className = filename.substring(0, filename.indexOf("."));
            URL manifestURL = null;
            ConfigurationFile manifesto;

            if (!file.isFile()) return false;

            // Load the jar file
            URLClassLoader jar = null;
            try {
                jar = new CanaryClassLoader(new URL[] { file.toURI().toURL() }, Thread.currentThread().getContextClassLoader());
            } catch (MalformedURLException ex) {
                LogManager.get().logStackTrace("Exception while loading Plugin jar", ex);
                return false;
            }

            // Load file information
            manifestURL = jar.getResource("CANARY.INF");
            if (manifestURL == null) {
                LogManager.get().logSevere("Failed to load plugin '" + className + "': resource CANARY.INF is missing.");
                return false;
            }

            // Parse the file
            manifesto = new ConfigurationFile(jar.getResourceAsStream("CANARY.INF"));

            // Find the mount-point to determine the load-time
            int mountType = 0; // 0 = no, 1 = pre, 2 = post // reused for dependencies
            String mount = manifesto.getString("mount-point", "after");
            if (mount.trim().equalsIgnoreCase("after") || mount.trim().equalsIgnoreCase("post")) mountType = 2;
            else if (mount.trim().equalsIgnoreCase("before") || mount.trim().equalsIgnoreCase("pre")) mountType = 1;
            else if (mount.trim().equalsIgnoreCase("no-load") || mount.trim().equalsIgnoreCase("none")) mountType = 0;
            else {
                LogManager.get().logSevere("Failed to load plugin " + className + ": resource CANARY.INF is invalid.");
                return false;
            }

            if (mountType == 2) this.postLoad.put(className.toLowerCase(), jar);
            else if (mountType == 1) this.preLoad.put(className.toLowerCase(), jar);
            else if (mountType == 0) { // Do not load, close jar
                this.noLoad.add(className.toLowerCase());
                return true;
            }

            // Find dependencies and put them in the dependency order-list
            String[] dependencies = manifesto.getString("dependencies", "").split("[,;]");
            ArrayList<String> depends = new ArrayList<String>();
            for (String dependency : dependencies) {
                dependency = dependency.trim();

                // Remove empty entries
                if (dependency == "") continue;

                // Remove duplicates
                if (depends.contains(dependency.toLowerCase())) continue;

                depends.add(dependency.toLowerCase());
            }
            if (mountType == 2) // post
            this.postLoadDependencies.put(className.toLowerCase(), depends);
        } catch (Throwable ex) {
            LogManager.get().logStackTrace("Exception while scanning plugin", ex);
            return false;
        }

        return true;
    }

    /**
     * The class loader
     * 
     * @param pluginName
     * @param jar
     * @return
     */
    private boolean load(String pluginName, URLClassLoader jar) {
        try {
            String mainClass = "";

            try {
                // Get the path of a known resource
                String infPath = jar.getResource("CANARY.INF").toString();
                // Remove the resource and directly point to the manifest
                String path = infPath.substring(0, infPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
                // Get a manifest object
                Manifest manifest = new Manifest(new URL(path).openStream());
                // Get the main Main-Class attribute
                Attributes attr = manifest.getMainAttributes();
                mainClass = attr.getValue("Main-Class");
            } catch (IOException e) {
                LogManager.get().logStackTrace("Failed to load manifest of plugin '" + pluginName + "'.", e);
                return false;
            }

            if (mainClass == "") {
                LogManager.get().logSevere("Failed to find Manifest in plugin '" + pluginName + "'");
                return false;
            }

            Class<?> c = jar.loadClass(mainClass);
            Plugin plugin = (Plugin) c.newInstance();

            synchronized (lock) {
                this.plugins.add(plugin);
                plugin.enable();
            }
        } catch (Throwable ex) {
            LogManager.get().logStackTrace("Exception while loading plugin '" + pluginName + "'", ex);
            return false;
        }

        return true;
    }

    /**
     * Start solving the dependency list given.
     * 
     * @param pluginDependencies
     * @return
     */
    private ArrayList<String> solveDependencies(HashMap<String, ArrayList<String>> pluginDependencies) {
        // http://www.electricmonk.nl/log/2008/08/07/dependency-resolving-algorithm/

        if (pluginDependencies.size() == 0) return new ArrayList<String>();

        ArrayList<String> retOrder = new ArrayList<String>();
        HashMap<String, CanaryPluginDependencyNode> graph = new HashMap<String, CanaryPluginDependencyNode>();

        // Create the node list
        for (String name : pluginDependencies.keySet()) {
            graph.put(name, new CanaryPluginDependencyNode(name));
        }

        // Add dependency nodes to the nodes
        ArrayList<String> isDependency = new ArrayList<String>();
        for (String pluginName : pluginDependencies.keySet()) {
            CanaryPluginDependencyNode node = graph.get(pluginName);
            for (String depName : pluginDependencies.get(pluginName)) {
                if (!graph.containsKey(depName)) {
                    // Dependency does not exist, lets happily fail
                    LogManager.get().logWarning("Failed to solve dependency '" + depName + "'");
                    continue;
                }
                node.addEdge(graph.get(depName));
                isDependency.add(depName);
            }
        }

        // Remove nodes in the top-list that are in the graph too
        for (String dep : isDependency) {
            graph.remove(dep);
        }

        // If there are no nodes anymore, there might have been a circular dependency
        if (graph.size() == 0) {
            LogManager.get().logWarning("Failed to solve dependency graph. Is there a circular dependency?");
            return null;
        }

        // The graph now contains elements that either have edges or are lonely

        ArrayList<CanaryPluginDependencyNode> resolved = new ArrayList<CanaryPluginDependencyNode>();
        for (String n : graph.keySet()) {

            this.depResolve(graph.get(n), resolved);
        }

        for (CanaryPluginDependencyNode x : resolved)
            retOrder.add(x.getName());

        return retOrder;
    }

    /**
     * This recursive method actually solves the dependency lists
     * 
     * @param node
     * @param resolved
     */
    private void depResolve(CanaryPluginDependencyNode node, ArrayList<CanaryPluginDependencyNode> resolved) {
        for (CanaryPluginDependencyNode edge : node.edges) {
            if (!resolved.contains(edge)) this.depResolve(edge, resolved);
        }
        resolved.add(node);
    }

    public Plugin getPlugin(String name) {
        synchronized (lock) {
            for (Plugin plugin : plugins) {
                if (plugin.getName().equalsIgnoreCase(name)) {
                    return plugin;
                }
            }
        }

        return null;
    }

    public String[] getPluginList() {
        ArrayList<String> list = new ArrayList<String>();
        String[] ret = {};

        synchronized (lock) {
            for (Plugin plugin : this.plugins) {
                list.add(plugin.getName());
            }
        }

        return list.toArray(ret);
    }

    public String getReadablePluginList() {
        StringBuilder sb = new StringBuilder();

        synchronized (lock) {
            for (Plugin plugin : plugins) {
                sb.append(plugin.getName());
                sb.append(" ");
                //sb.append(plugin.isEnabled() ? "(E)" : "(D)");
                sb.append(",");
            }
        }
        String str = sb.toString();

        if (str.length() > 1) {
            return str.substring(0, str.length() - 1);
        } else {
            return "Empty";
        }
    }

    public boolean enablePlugin(String name) {
        Plugin plugin = this.getPlugin(name);
        if (plugin == null) return false;

        //if(plugin.isEnabled())
        //	return true;

        plugin.enable();

        return true;
    }

    public boolean disablePlugin(String name) {
        Plugin plugin = this.getPlugin(name);
        if (plugin == null) return false;

        //if(!plugin.isEnabled())
        //	return true;

        plugin.disable();

        return true;
    }

}