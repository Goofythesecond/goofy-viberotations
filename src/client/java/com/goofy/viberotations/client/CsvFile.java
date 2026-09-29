package com.goofy.viberotations.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Buffered CSV writer plus a row builder that formats numbers locale-independently. */
final class CsvFile implements AutoCloseable {

	private final BufferedWriter out;
	private int rowsSinceFlush;

	CsvFile(Path path, String... header) throws IOException {
		out = Files.newBufferedWriter(path);
		out.write(String.join(",", header));
		out.newLine();
	}

	void write(Row row) throws IOException {
		out.append(row.sb, 1, row.sb.length());
		out.newLine();
		if (++rowsSinceFlush >= 100) {
			out.flush();
			rowsSinceFlush = 0;
		}
	}

	@Override
	public void close() throws IOException {
		out.close();
	}

	static final class Row {
		private final StringBuilder sb = new StringBuilder(512);

		Row add(double v) {
			sb.append(',').append(String.format(Locale.ROOT, "%.4f", v));
			return this;
		}

		Row add(long v) {
			sb.append(',').append(v);
			return this;
		}

		Row add(boolean v) {
			sb.append(',').append(v ? 1 : 0);
			return this;
		}

		Row add(String v) {
			sb.append(',');
			if (v != null) {
				sb.append(v.replace(',', ';'));
			}
			return this;
		}

		/** Appends {@code n} empty fields. */
		Row blank(int n) {
			for (int i = 0; i < n; i++) {
				sb.append(',');
			}
			return this;
		}
	}
}
