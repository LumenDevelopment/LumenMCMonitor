package cloud.lumenvm.api;

import java.io.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Context {

    private final File dataFolder;
    private final JarFile jar;

    public Context(File dataFolder, File jarFile) throws IOException {
        this.dataFolder = dataFolder;
        this.jar = new JarFile(jarFile);
    }

    public File getDataFolder() {
        return dataFolder;
    }

    public InputStream getResource(String path) throws IOException {
        JarEntry entry = jar.getJarEntry(path);
        return jar.getInputStream(entry);
    }

    public void saveResource(String path, String destination) throws IOException {
        File out = new File(dataFolder, destination);

        out.getParentFile().mkdirs();

        try (InputStream is = getResource(path);
             OutputStream os = new FileOutputStream(out)) {
            is.transferTo(os);
        }
        close();
    }

    public void close() throws IOException {
        jar.close();
    }
}
