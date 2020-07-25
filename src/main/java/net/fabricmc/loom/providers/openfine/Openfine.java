/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.io.FilenameUtils;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.logging.Logger;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;

import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MappingsProvider.MappingFactory;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.stitch.util.StitchUtil;
import net.fabricmc.stitch.util.StitchUtil.FileSystemDelegate;
import net.fabricmc.tinyremapper.IMappingProvider;

public class Openfine {
	public static final String VERSION = "cc6da75";

	public static File process(Logger logger, String mcVersion, File client, File server, File optifineJar) throws IOException {
		OptiFineVersion optifine = new OptiFineVersion(optifineJar);
		logger.info("Loaded OptiFine " + optifine.version);

		if (!optifine.supports(mcVersion)) {
			throw new InvalidUserDataException("Incompatible OptiFine version, requires " + optifine.minecraftVersion + " rather than " + mcVersion);
		}

		File optiCache = new File(client.getParentFile(), "optifine");
		optiCache.mkdirs();

		if (optifine.isInstaller) {
			File installer = optifineJar;
			optifineJar = new File(optiCache, FilenameUtils.removeExtension(optifineJar.getName()) + "-extract.jar");
			if (!optifineJar.exists()) extract(logger, client, installer, optifineJar);
		}

		File merged = new File(optiCache, FilenameUtils.removeExtension(client.getName()) + "-optifined.jar");
		if (!merged.exists()) merge(logger, client, optifineJar, server, merged);

		return merged;
	}

	private static void extract(Logger logger, File minecraft, File installer, File to) throws IOException {
		logger.info("Extracting OptiFine into " + to);

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, Openfine.class.getClassLoader())) {
			Class<?> clazz = classLoader.loadClass("optifine.Patcher");
			Method method = clazz.getDeclaredMethod("process", File.class, File.class, File.class);
			method.invoke(null, minecraft, installer, to);
		} catch (MalformedURLException e) {
			throw new RuntimeException("Unable to use OptiFine jar at " + installer.getAbsolutePath(), e);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Error running OptiFine installer", e);
		}
	}

	private static void merge(Logger logger, File client, File optifine, File server, File to) throws IOException {
		logger.info("Merging OptiFine into " + to);

		Set<String> mcEntries, optifineEntries, intersection;
		try (JarFile mcJar = new JarFile(client); JarFile optifineJar = new JarFile(optifine)) {
			//Comparison on ZipEntries is poorly defined so we'll use the entry names for equality
			mcEntries = ImmutableSet.copyOf(Iterators.transform(Iterators.forEnumeration(mcJar.entries()), JarEntry::getName));
			optifineEntries = ImmutableSet.copyOf(Iterators.filter(Iterators.transform(Iterators.forEnumeration(optifineJar.entries()), JarEntry::getName), name -> !name.startsWith("srg/")));

			if (mcEntries.size() > optifineEntries.size()) {
				intersection = Sets.intersection(optifineEntries, mcEntries);
			} else {
				intersection = Sets.intersection(mcEntries, optifineEntries);
			}
		}

		try (FileSystemDelegate mcFS = StitchUtil.getJarFileSystem(client, false);
				FileSystemDelegate ofFS = StitchUtil.getJarFileSystem(optifine, false);
				FileSystemDelegate serverFS = server.exists() ? StitchUtil.getJarFileSystem(server, false) : null;
				FileSystemDelegate outputFS = StitchUtil.getJarFileSystem(to, true)) {
			for (String entry : Sets.difference(mcEntries, optifineEntries)) {
				copy(mcFS.get(), outputFS.get(), entry);
			}

			for (String entry : Sets.difference(optifineEntries, mcEntries)) {
				copy(ofFS.get(), outputFS.get(), entry);
			}

			for (String entry : intersection) {
				if (entry.endsWith(".class")) {
					Path pathRawIn = mcFS.get().getPath(entry);
					Path pathPatchedIn = ofFS.get().getPath(entry);

					Path pathOut = outputFS.get().getPath(entry);
			        if (pathOut.getParent() != null) {
			            Files.createDirectories(pathOut.getParent());
			        }

			        byte[] stitchFix;
			        if (serverFS != null) {
			        	Path pathStichFix = serverFS.get().getPath(entry);
				        stitchFix = Files.isReadable(pathStichFix) ? Files.readAllBytes(pathStichFix) : null;
			        } else {
			        	stitchFix = null;
			        }

			        logger.info("Reconstructing " + entry);
			        byte[] data = ClassReconstructor.reconstruct(logger, Files.readAllBytes(pathRawIn), Files.readAllBytes(pathPatchedIn), stitchFix);

			        //BasicFileAttributes touchTime = Files.readAttributes(pathIn, BasicFileAttributes.class);
			        Files.write(pathOut, data, StandardOpenOption.CREATE_NEW);
			        //Files.getFileAttributeView(pathIn, BasicFileAttributeView.class).setTimes(touchTime.lastModifiedTime(), touchTime.lastAccessTime(), touchTime.creationTime());
				} else if (entry.startsWith("META-INF/")) {
					copy(mcFS.get(), outputFS.get(), entry);
				} else {
					copy(ofFS.get(), outputFS.get(), entry);
				}
			}
		} catch (IllegalStateException e) {
			//If an ISE is thrown something has clearly gone wrong with the merging of the jars, thus we don't want to keep the corrupted output
			if (!to.delete()) to.deleteOnExit();
			throw e;
		}
	}

	private static void copy(FileSystem fsIn, FileSystem fsOut, String entry) throws IOException {
		Path pathIn = fsIn.getPath(entry);

		Path pathOut = fsOut.getPath(entry);
        if (pathOut.getParent() != null) {
            Files.createDirectories(pathOut.getParent());
        }

        BasicFileAttributes touchTime = Files.readAttributes(pathIn, BasicFileAttributes.class);
        Files.copy(pathIn, pathOut);
        Files.getFileAttributeView(pathIn, BasicFileAttributeView.class).setTimes(touchTime.lastModifiedTime(), touchTime.lastAccessTime(), touchTime.creationTime());
	}

	public static void applyBonusMappings(MappingsProvider mappingsProvider) throws IOException {
		List<FieldEntry> extra = new ArrayList<>();

		for (FieldEntry field : mappingsProvider.getMappings().getFieldEntries()) {
			String interName = field.get("intermediary").getName();

			switch (interName) {
			case "field_1937": //Option#CLOUDS
				extra.add(namespace -> {
					EntryTriple real = field.get(namespace);
					return new EntryTriple(real.getOwner(), "official".equals(namespace) ? "CLOUDS" : "CLOUDS_OF", real.getDesc());
				});
				break;

			case "field_4062": //WorldRenderer#renderDistance
				extra.add(namespace -> {
					EntryTriple real = field.get(namespace);
					return new EntryTriple(real.getOwner(), "official".equals(namespace) ? "renderDistance" : "renderDistance_OF", real.getDesc());
				});
				break;
			}
		}

		mappingsProvider.mcRemappingFactory = new MappingFactory() {
			private final MappingFactory factory = mappingsProvider.mcRemappingFactory;

			@Override
			public IMappingProvider create(String fromMapping, String toMapping) throws IOException {
				return new IMappingProvider() {
					private final IMappingProvider wrapped = factory.create(fromMapping, toMapping);

					private void addExtras(Map<String, String> fields) {
						for (FieldEntry field : extra) {
							TinyRemapperMappingsHelper.add(field, fromMapping, toMapping, fields);
						}
					}

					@Override
					public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) {
						wrapped.load(classMap, fieldMap, methodMap, localMap);
						addExtras(fieldMap);
					}

					@Override
					public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
						wrapped.load(classMap, fieldMap, methodMap);
						addExtras(fieldMap);
					}

					@Override
					public String suggestLocalName(String type, boolean plural) {
						return wrapped.suggestLocalName(type, plural);
					}
				};
			}
		};
	}
}