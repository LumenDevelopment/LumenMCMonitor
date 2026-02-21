package cloud.lumenvm.api;

import java.io.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**Used to create addon datafolder and extract resources from itself*/
public class AddonContext {

    /**Addon's folder*/
    private final File addonFolder;
    /**{@link JarFile}*/
    private final JarFile jar;

    /**
     * Sets {@link #addonFolder} and {@link #jar}
     * @param dataFolder addon's folder
     * @param jarFile addon jar
     * @throws IOException if setting the {@link #jar} fails
     * */
    public AddonContext(File dataFolder, File jarFile) throws IOException {
        this.addonFolder = dataFolder;
        this.jar = new JarFile(jarFile);
    }

    /**@return addon's folder*/
    public File getDataFolder() {
        return addonFolder;
    }

    /**
     * @return {@link InputStream} of addon's resource
     * @param path path of the resource
     * @throws IOException if getting the {@link InputStream} fails
     * */
    public InputStream getResource(String path) throws IOException {
        JarEntry entry = jar.getJarEntry(path);
        return jar.getInputStream(entry);
    }

    /**
     * Saves resource in a destination
     * @param path path of the resource
     * @param destination destination path of the resource
     * @throws IOException if saving the resource fails
     * */
    public void saveResource(String path, String destination) throws IOException {
        File out = new File(addonFolder, destination);

        out.getParentFile().mkdirs();

        try (InputStream is = getResource(path);
             OutputStream os = new FileOutputStream(out)) {
            is.transferTo(os);
        }
    }

    /**
     * Closes the jar file
     * @throws IOException if closing the {@link #jar} fails
     * */
    public void close() throws IOException {
        jar.close();
    }
}
