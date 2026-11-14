package com.vynk.coparent_backend.common.storage;

import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/** {@link FileStorage} dans un dossier du disque ({@code app.storage.dir}). Écriture atomique. */
@Component
public class DiskFileStorage implements FileStorage {

    /** Segments de lettres, chiffres, tirets, points et soulignés ; jamais « .. ». */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

    private final Path root;

    public DiskFileStorage(StorageProperties properties) {
        this.root = Path.of(properties.dir()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Échec de l'enregistrement du fichier " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Échec de la lecture du fichier " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Échec de la suppression du fichier " + key, e);
        }
    }

    @Override
    public void deleteFolder(String prefix) {
        try {
            FileSystemUtils.deleteRecursively(resolve(prefix));
        } catch (IOException e) {
            throw new UncheckedIOException("Échec de la suppression du dossier " + prefix, e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Clé de fichier invalide : " + key);
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Clé de fichier invalide : " + key);
        }
        return path;
    }
}
