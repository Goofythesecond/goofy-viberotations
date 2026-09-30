package com.goofy.viberotations.client;

import com.goofy.viberotations.train.AimSeed;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Seeds live in {@code rotation-logs/training/seeds/seed_<id>.json}; {@code current.txt} holds the
 * id of the one in use. Rejected seeds are kept too, so their history (and parts) stay available.
 */
final class SeedStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();

	private final Path dir;

	SeedStore(Path trainingDir) {
		this.dir = trainingDir.resolve("seeds");
	}

	AimSeed current() throws IOException {
		Path p = dir.resolve("current.txt");
		if (!Files.exists(p)) {
			return null;
		}
		return load(Integer.parseInt(Files.readString(p).trim()));
	}

	void setCurrent(int id) throws IOException {
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("current.txt"), Integer.toString(id));
	}

	AimSeed load(int id) throws IOException {
		Path p = file(id);
		return Files.exists(p) ? GSON.fromJson(Files.readString(p), AimSeed.class) : null;
	}

	void save(AimSeed seed) throws IOException {
		Files.createDirectories(dir);
		if (seed.created.isEmpty()) {
			seed.created = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		}
		Files.writeString(file(seed.id), GSON.toJson(seed));
	}

	List<AimSeed> all() throws IOException {
		List<AimSeed> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.filter(f -> f.getFileName().toString().matches("seed_\\d+\\.json")).toList()) {
				out.add(GSON.fromJson(Files.readString(p), AimSeed.class));
			}
		}
		out.sort(Comparator.comparingInt(a -> a.id));
		return out;
	}

	int nextId() throws IOException {
		return all().stream().mapToInt(s -> s.id).max().orElse(0) + 1;
	}

	private Path file(int id) {
		return dir.resolve(String.format("seed_%04d.json", id));
	}
}
