/*******************************************************************************
 * Copyright (c) 2014 IBM Corporation and others 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.cf.manifest.v2.utils;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.core.filesystem.*;
import org.eclipse.core.runtime.*;
import org.eclipse.orion.server.cf.manifest.v2.*;
import org.eclipse.orion.server.cf.service.DeploymentDescription;
import org.eclipse.osgi.util.NLS;

public class ManifestUtils {

	private static final Pattern NON_LATIN_PATTERN = Pattern.compile("[^\\w-]");
	private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\s]");

	public static final String[] RESERVED_PROPERTIES = {//
	"env", // //$NON-NLS-1$
			"inherit", // //$NON-NLS-1$
			"applications" // //$NON-NLS-1$
	};

	public static boolean isReserved(ManifestParseTree node) {
		String value = node.getLabel();
		for (String property : RESERVED_PROPERTIES)
			if (property.equals(value))
				return true;

		return false;
	}

	/**
	 * Inner helper method parsing single manifests with additional semantic analysis.
	 */
	private static ManifestParseTree parseManifest(InputStream manifestInputStream, String targetBase) throws CoreException, IOException, TokenizerException, ParserException, AnalyzerException {

		/* run preprocessor */
		ManifestPreprocessor preprocessor = new ManifestPreprocessor();
		List<InputLine> inputLines = preprocessor.process(manifestInputStream);

		/* run parser */
		ManifestTokenizer tokenizer = new ManifestTokenizer(inputLines);
		ManifestParser parser = new ManifestParser();
		ManifestParseTree parseTree = parser.parse(tokenizer);

		/* perform inheritance transformations */
		ManifestTransformator transformator = new ManifestTransformator();
		transformator.apply(parseTree);

		/* resolve symbols */
		SymbolResolver symbolResolver = new SymbolResolver(targetBase);
		symbolResolver.apply(parseTree);

		/* validate common field values */
		ApplicationSanizator applicationAnalyzer = new ApplicationSanizator();
		applicationAnalyzer.apply(parseTree);
		return parseTree;
	}

	/**
	 * Utility method wrapping the manifest parse process including inheritance and additional semantic analysis.
	 * @param sandbox The file store used to limit manifest inheritance, i.e. each parent manifest has to be a
	 *  transitive child of the sandbox.
	 * @param manifestStore Manifest file store used to fetch the manifest contents.
	 * @param targetBase Cloud foundry target base used to resolve manifest symbols.
	 * @param manifestList List of forbidden manifest paths considered in the recursive inheritance process.
	 * Used to detect inheritance cycles.
	 * @return An intermediate manifest tree representation.
	 * @throws CoreException
	 * @throws IOException
	 * @throws TokenizerException
	 * @throws ParserException
	 * @throws AnalyzerException
	 * @throws InvalidAccessException
	 */
	public static ManifestParseTree parse(IFileStore sandbox, IFileStore manifestStore, String targetBase, List<IPath> manifestList) throws CoreException, IOException, TokenizerException, ParserException, AnalyzerException, InvalidAccessException {

		/* basic sanity checks */
		IFileInfo manifestFileInfo = manifestStore.fetchInfo();
		if (!manifestFileInfo.exists() || manifestFileInfo.isDirectory())
			throw new IOException(ManifestConstants.MISSING_OR_INVALID_MANIFEST);

		if (manifestFileInfo.getLength() == EFS.NONE || manifestFileInfo.getLength() > ManifestConstants.MANIFEST_SIZE_LIMIT)
			throw new IOException(ManifestConstants.MANIFEST_FILE_SIZE_EXCEEDED);

		InputStream manifestInputStream = manifestStore.openInputStream(EFS.NONE, null);
		ManifestParseTree manifest = parseManifest(manifestInputStream, targetBase);

		if (!manifest.has(ManifestConstants.INHERIT))
			/* nothing to do */
			return manifest;

		/* check if the parent manifest is within the given sandbox */
		IPath parentLocation = new Path(manifest.get(ManifestConstants.INHERIT).getValue());
		if (!InheritanceUtils.isWithinSandbox(sandbox, manifestStore, parentLocation))
			throw new AnalyzerException(NLS.bind(ManifestConstants.FORBIDDEN_ACCESS_ERROR, manifest.get(ManifestConstants.INHERIT).getValue()));

		/* detect inheritance cycles */
		if (manifestList.contains(parentLocation))
			throw new AnalyzerException(ManifestConstants.INHERITANCE_CYCLE_ERROR);

		manifestList.add(parentLocation);

		IFileStore parentStore = manifestStore.getParent().getFileStore(parentLocation);
		ManifestParseTree parentManifest = parse(sandbox, parentStore, targetBase, manifestList);
		InheritanceUtils.inherit(parentManifest, manifest);

		/* perform additional inheritance transformations */
		ManifestTransformator transformator = new ManifestTransformator();
		transformator.apply(manifest);
		return manifest;
	}

	/**
	 * Helper method for {@link #parse(IFileStore, IFileStore, String, List<IPath>)}
	 * @param sandbox
	 * @param manifestStore
	 * @return
	 * @throws CoreException
	 * @throws IOException
	 * @throws TokenizerException
	 * @throws ParserException
	 * @throws AnalyzerException
	 * @throws InvalidAccessException
	 */
	public static ManifestParseTree parse(IFileStore sandbox, IFileStore manifestStore) throws CoreException, IOException, TokenizerException, ParserException, AnalyzerException, InvalidAccessException {
		return parse(sandbox, manifestStore, null, new ArrayList<IPath>());
	}

	/**
	 * Helper method for {@link #parse(IFileStore, IFileStore, String, List<IPath>)}
	 * @param sandbox
	 * @param manifestStore
	 * @param targetBase
	 * @return
	 * @throws CoreException
	 * @throws IOException
	 * @throws TokenizerException
	 * @throws ParserException
	 * @throws AnalyzerException
	 * @throws InvalidAccessException
	 */
	public static ManifestParseTree parse(IFileStore sandbox, IFileStore manifestStore, String targetBase) throws CoreException, IOException, TokenizerException, ParserException, AnalyzerException, InvalidAccessException {
		return parse(sandbox, manifestStore, targetBase, new ArrayList<IPath>());
	}

	/**
	 * Utility method wrapping the non-manifest, deployment description parse process.
	 * @param deploymentDescription The single application, non-manifest deployment description.
	 * @param targetBase Cloud foundry target base used to resolve manifest symbols.
	 * @return An intermediate manifest tree representation.
	 * @throws CoreException
	 * @throws IOException
	 * @throws TokenizerException
	 * @throws ParserException
	 * @throws AnalyzerException
	 */
	public static ManifestParseTree parse(DeploymentDescription deploymentDescription, String targetBase) throws CoreException, IOException, TokenizerException, ParserException, AnalyzerException {
		InputStream manifestInputStream = new ByteArrayInputStream(deploymentDescription.toString().getBytes());
		return parseManifest(manifestInputStream, targetBase);
	}

	/**
	 * Helper method for {@link #parse(DeploymentDescription, String)}
	 * @param deploymentDescription
	 * @return
	 * @throws CoreException
	 * @throws IOException
	 * @throws TokenizerException
	 * @throws ParserException
	 * @throws AnalyzerException
	 */
	public static ManifestParseTree parse(DeploymentDescription deploymentDescription) throws CoreException, IOException, TokenizerException, ParserException, AnalyzerException {
		return parse(deploymentDescription, null);
	}

	/**
	 * Normalizes the string memory measurement to a MB integer value.
	 * @param memory Manifest memory measurement.
	 * @return Normalized MB integer value.
	 */
	public static int normalizeMemoryMeasure(String memory) {

		if (memory.toLowerCase().endsWith("m")) //$NON-NLS-1$
			return Integer.parseInt(memory.substring(0, memory.length() - 1));

		if (memory.toLowerCase().endsWith("mb")) //$NON-NLS-1$
			return Integer.parseInt(memory.substring(0, memory.length() - 2));

		if (memory.toLowerCase().endsWith("g")) //$NON-NLS-1$
			return (1024 * Integer.parseInt(memory.substring(0, memory.length() - 1)));

		if (memory.toLowerCase().endsWith("gb")) //$NON-NLS-1$
			return (1024 * Integer.parseInt(memory.substring(0, memory.length() - 2)));

		/* return default memory value, i.e. 1024 MB */
		return 1024;
	}

	/**
	 * Creates a slug for the given input. Used to create default
	 * host names based on application names.
	 * @param input Input string, e. g. application name.
	 * @return Input slug.
	 */
	public static String slugify(String input) {
		String intermediate = WHITESPACE_PATTERN.matcher(input).replaceAll("-");
		return NON_LATIN_PATTERN.matcher(intermediate).replaceAll("").toLowerCase();
	}
}