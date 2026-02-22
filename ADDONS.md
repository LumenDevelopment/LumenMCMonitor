# Addons

## Premade addons

Some premade addons can be found in our [addon repo](https://github.com/LumenDevelopment/LumenMCMonitor-Addons).

## Create addons with LumenMC Monitor API!

Adding to your project:
```groovy
repositories {
    maven {url 'https://hub.spigotmc.org/nexus/content/repositories/snapshots/' }
    maven {url 'https://maven.lumenvm.cloud/repository/public/'}
}

dependencies {
    compileOnly 'cloud.lumenvm:lumenmc-monitor:dev2026-02-20-173230'
    compileOnly 'org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT'
}
```

You can take the advantage of our template [addon repo](https://github.com/LumenDevelopment/LumenMCMonitor-Addons).