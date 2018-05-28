package atm.bloodworkxgaming.serverstarter.packtype.curse;

import atm.bloodworkxgaming.serverstarter.FileManager;
import atm.bloodworkxgaming.serverstarter.config.ConfigFile;
import atm.bloodworkxgaming.serverstarter.packtype.IPackType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static atm.bloodworkxgaming.serverstarter.ServerStarter.LOGGER;

public class CursePackType implements IPackType {
    private ConfigFile configFile;
    private String basePath;
    private String forgeVersion;
    private String mcVersion;
    private File oldFiles;

    public CursePackType(ConfigFile configFile) {
        this.configFile = configFile;
        basePath = configFile.install.baseInstallPath;
        forgeVersion = configFile.install.forgeVersion;
        mcVersion = configFile.install.mcVersion;
        oldFiles = new File(basePath + "OLD_TO_DELETE/");
    }

    @Override
    public void installPack() {
        if (configFile.install.modpackUrl != null && !configFile.install.modpackUrl.isEmpty()) {
            String url = configFile.install.modpackUrl;
            if (!url.endsWith("/download"))
                url += "/download";

            try {
                List<PathMatcher> patterns = configFile.install.ignoreFiles
                        .stream()
                        .map(s -> {
                            if (s.startsWith("glob:") || s.startsWith("regex:"))
                                return s;
                            else
                                return "glob:" + s;
                        })
                        .map(FileSystems.getDefault()::getPathMatcher)
                        .collect(Collectors.toList());

                unzipFile(downloadPack(url), patterns);
                // unzipFile(new File(basePath + "modpack-download.zip"));
                handleManifest();

            } catch (IOException e) {
                e.printStackTrace();
            }


        } else if (configFile.install.formatSpecific.containsKey("packid") && configFile.install.formatSpecific.containsKey("fileid")) {
            try {
                HttpResponse<JsonNode> res = Unirest.get("/api/v2/direct/GetAddOnFile/" + configFile.install.formatSpecific.get("packid") + "/" + configFile.install.formatSpecific.get("fileid")).asJson();
                LOGGER.info("PackID request response: " + res);
            } catch (UnirestException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Gets the forge version, can be based on the version from the downloaded pack
     *
     * @return String representation of the version
     */
    @Override
    public String getForgeVersion() {
        return forgeVersion;
    }

    /**
     * Gets the forge version, can be based on the version from the downloaded pack
     *
     * @return String representation of the version
     */
    @Override
    public String getMCVersion() {
        return mcVersion;
    }

    /**
     * Downloads the modpack from the given url
     *
     * @param url URL to download from
     * @return File of the saved modpack zip
     * @throws IOException if something went wrong while downloading
     */
    private File downloadPack(String url) throws IOException {
        LOGGER.info("Attempting to download modpack Zip.");

        try {
            File to = new File(basePath + "modpack-download.zip");

            FileUtils.copyURLToFile(new URL(url), to);
            LOGGER.info("Downloaded the modpack zip file to " + to.getAbsolutePath());

            return to;

        } catch (IOException e) {
            LOGGER.error("Pack could not be downloaded");
            throw e;
        }
    }

    private void unzipFile(File downloadedPack, List<PathMatcher> patterns) throws IOException {
        // delete old installer folder
        FileUtils.deleteDirectory(oldFiles);

        // start with deleting the mods folder as it is not garanteed to have override mods
        File modsFolder = new File(basePath + "mods/");
        if (modsFolder.exists())
            FileUtils.moveDirectory(modsFolder, new File(oldFiles, "mods"));
        LOGGER.info("Moved the mods folder");

        LOGGER.info("Starting to unzip files.");
        // unzip start
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(downloadedPack))) {
            ZipEntry entry = zis.getNextEntry();

            byte[] buffer = new byte[1024];

            while (entry != null) {
                LOGGER.info("Entry in zip: " + entry, true);
                String name = entry.getName();

                // special manifest treatment
                if (name.equals("manifest.json")) {

                    File manifestFile = new File(basePath + "manifest.json");
                    try (FileOutputStream fos = new FileOutputStream(manifestFile)) {

                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }


                // overrides
                if (name.startsWith("overrides/")) {

                    String path = entry.getName().substring(10);
                    Path p = Paths.get(path);
                    if (patterns.stream().anyMatch(pattern -> pattern.matches(p))) {
                        LOGGER.info("Skipping " + path + " as it is on the ignore List.", true);

                        entry = zis.getNextEntry();
                        continue;
                    }

                    if (!name.endsWith("/")) {
                        File outfile = new File(basePath + path);
                        LOGGER.info("Copying zip entry to = " + outfile, true);

                        //noinspection ResultOfMethodCallIgnored
                        String parent = outfile.getParent();
                        if (parent != null)
                            new File(parent).mkdirs();

                        try (FileOutputStream fos = new FileOutputStream(outfile)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }

                    } else if (!name.equals("overrides/")) {
                        File newFolder = new File(basePath + path);
                        if (newFolder.exists())
                            FileUtils.moveDirectory(newFolder, new File(oldFiles, path));

                        LOGGER.info("Folder moved: " + newFolder.getAbsolutePath(), true);
                    }
                }


                entry = zis.getNextEntry();
            }


            zis.closeEntry();
        } catch (IOException e) {
            LOGGER.error("Could not unzip files", e);
        }

        LOGGER.info("Done unzipping the files.");
    }

    private void handleManifest() throws IOException {
        List<ModEntryRaw> mods = new ArrayList<>();

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(new File(basePath + "manifest.json")), "utf-8")) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            LOGGER.info("manifest JSON Object: " + json, true);
            JsonObject mcObj = json.getAsJsonObject("minecraft");

            if (mcVersion == null) {
                mcVersion = mcObj.getAsJsonPrimitive("version").getAsString();
            }

            // gets the forge version
            if (forgeVersion == null) {
                JsonArray loaders = mcObj.getAsJsonArray("modLoaders");
                if (loaders.size() > 0) {
                    forgeVersion = loaders.get(0).getAsJsonObject().getAsJsonPrimitive("id").getAsString().substring(6);
                }
            }

            // gets all the mods
            for (JsonElement jsonElement : json.getAsJsonArray("files")) {
                JsonObject obj = jsonElement.getAsJsonObject();
                mods.add(new ModEntryRaw(
                        obj.getAsJsonPrimitive("projectID").getAsString(),
                        obj.getAsJsonPrimitive("fileID").getAsString()));
            }
        }

        downloadMods(mods);
    }

    /**
     * Downloads the mods specified in the manifest
     * Gets the data from cursemeta
     *
     * @param mods List of the mods from the manifest
     */
    private void downloadMods(List<ModEntryRaw> mods) {
        Set<String> ignoreSet = new HashSet<>();
        List<Object> ignoreListTemp = configFile.install.getFormatSpecificSettingOrDefault("ignoreProject", null);

        if (ignoreListTemp != null)
            for (Object o : ignoreListTemp) {
                if (o instanceof String)
                    ignoreSet.add((String) o);

                if (o instanceof Integer)
                    ignoreSet.add(String.valueOf(o));
            }


        ConcurrentLinkedQueue<String> urls = new ConcurrentLinkedQueue<>();

        LOGGER.info("Requesting Download links from cursemeta.");

        mods.parallelStream().forEach(mod -> {
            if (!ignoreSet.isEmpty() && ignoreSet.contains(mod.projectID)) {
                LOGGER.info("Skipping mod with projectID: " + mod.projectID);
                return;
            }

            String url = configFile.install.getFormatSpecificSettingOrDefault("cursemeta", "https://cursemeta.dries007.net")
                    + "/" + mod.projectID + "/" + mod.fileID + ".json";
            LOGGER.info("Download url is: " + url, true);

            try {
                HttpResponse<JsonNode> res = Unirest
                        .get(url)
                        .header("User-Agent", "All the mods server installer.")
                        .header("Content-Type", "application/json")
                        .asJson();

                if (res.getStatus() != 200)
                    throw new UnirestException("Response was not OK");

                JsonObject jsonRes = new JsonParser().parse(res.getBody().toString()).getAsJsonObject();
                LOGGER.info("Response from manifest query: " + jsonRes, true);

                urls.add(jsonRes
                        .getAsJsonObject()
                        .getAsJsonPrimitive("DownloadURL").getAsString());

            } catch (UnirestException e) {
                LOGGER.error("Error while trying to get URL from cursemeta for mod " + mod.projectID, e);
            }
        });

        LOGGER.info("Mods to download: " + urls, true);

        processMods(urls);

    }

    //region >>>>>>> Stuff for when cursemeta works again:
    /*
    private void downloadMods(List<ModEntryRaw> mods) {
        Set<String> ignoreSet = new HashSet<>();
        List<Object> ignoreListTemp = configFile.install.getFormatSpecificSettingOrDefault("ignoreProject", null);

        if (ignoreListTemp != null)
            for (Object o : ignoreListTemp) {
                if (o instanceof String)
                    ignoreSet.add((String) o);

                if (o instanceof Integer)
                    ignoreSet.add(String.valueOf(o));
            }

        // constructs the body
        JsonObject request = new JsonObject();
        JsonArray array = new JsonArray();
        for (ModEntryRaw mod : mods) {
            if (!ignoreSet.isEmpty() && ignoreSet.contains(mod.projectID)) {
                LOGGER.info("Skipping mod with projectID: " + mod.projectID);
                continue;
            }

            JsonObject objMod = new JsonObject();
            objMod.addProperty("AddOnID", mod.projectID);
            objMod.addProperty("FileID", mod.fileID);
            array.add(objMod);
        }
        request.add("addOnFileKeys", array);

        LOGGER.info("Requesting Download links from cursemeta.");
        LOGGER.info("About to make a request to cursemeta with body: " + request.toString(), true);

        try {
            HttpResponse<JsonNode> res = Unirest
                    .post(configFile.install.getFormatSpecificSettingOrDefault("cursemeta", "https://cursemeta.dries007.net")
                            + "/api/v2/direct/GetAddOnFiles")
                    .header("User-Agent", "All the mods server installer.")
                    .header("Content-Type", "application/json")
                    .body(request.toString())
                    .asJson();

            if (res.getStatus() != 200)
                throw new UnirestException("Response was not OK");

            // Gets the download links for the mods
            List<String> modsToDownload = new ArrayList<>();
            JsonArray jsonRes = new JsonParser().parse(res.getBody().toString()).getAsJsonArray();
            for (JsonElement modEntry : jsonRes) {
                modsToDownload.add(modEntry
                        .getAsJsonObject()
                        .getAsJsonArray("Value").get(0)
                        .getAsJsonObject()
                        .getAsJsonPrimitive("DownloadURL").getAsString());
            }

            LOGGER.info("Response from manifest query: " + jsonRes, true);
            LOGGER.info("Mods to download: " + modsToDownload, true);
            processMods(modsToDownload);

        } catch (UnirestException e) {
            e.printStackTrace();
        }
    }*/
    //endregion

    /**
     * Downloads all mods, with a second fallback if failed
     * This is done in parrallel for better performance
     *
     * @param mods List of urls
     */
    private void processMods(Collection<String> mods) {
        // constructs the ignore list
        List<Pattern> ignorePatterns = new ArrayList<>();
        for (String ignoreFile : configFile.install.ignoreFiles) {
            if (ignoreFile.startsWith("mods/")) {
                ignorePatterns.add(Pattern.compile(ignoreFile.substring(ignoreFile.lastIndexOf('/'))));
            }
        }

        // downloads the mods
        AtomicInteger count = new AtomicInteger(0);
        int totalCount = mods.size();
        List<String> fallbackList = new ArrayList<>();

        mods.stream().parallel().forEach(s -> processSingleMod(s, count, totalCount, fallbackList, ignorePatterns));

        List<String> secondFail = new ArrayList<>();
        fallbackList.forEach(s -> processSingleMod(s, count, totalCount, secondFail, ignorePatterns));

        if (!secondFail.isEmpty()) {
            LOGGER.warn("Failed to download (a) mod(s):");
            for (String s : secondFail) {
                LOGGER.warn("\t" + s);
            }
        }
    }

    /**
     * Downloads a single mod and saves to the /mods directory
     *
     * @param mod            URL of the mod
     * @param counter        current counter of how many mods have already been downloaded
     * @param totalCount     total count of mods that have to be downloaded
     * @param fallbackList   List to write to when it failed
     * @param ignorePatterns Patterns of mods which should be ignored
     */
    private void processSingleMod(String mod, AtomicInteger counter, int totalCount, List<String> fallbackList, List<Pattern> ignorePatterns) {
        try {
            String modName = FilenameUtils.getName(mod);
            for (Pattern ignorePattern : ignorePatterns) {
                if (ignorePattern.matcher(modName).matches()) {
                    LOGGER.info("[" + counter.incrementAndGet() + "/" + totalCount + "] Skipped ignored mod: " + modName);
                }
            }

            FileUtils.copyURLToFile(
                    FileManager.cleanUrl(mod),
                    new File(basePath + "mods/" + modName));
            LOGGER.info("[" + String.format("% 3d", counter.incrementAndGet()) + "/" + totalCount + "] Downloaded mod: " + modName);
        } catch (IOException e) {
            LOGGER.error("Failed to download mod", e);
            fallbackList.add(mod);
        } catch (URISyntaxException e) {
            LOGGER.error("Invalid url for " + mod, e);
        }
    }


    /**
     * Data class to keep projectID and fileID together
     */
    @AllArgsConstructor
    @ToString
    private static class ModEntryRaw {
        @Getter
        private String projectID;

        @Getter
        private String fileID;
    }
}
