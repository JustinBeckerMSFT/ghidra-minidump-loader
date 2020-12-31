package net.jubjubnest.minidump.plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.common.io.Files;

import ghidra.app.util.bin.format.pdb2.pdbreader.AbstractPdb;
import ghidra.app.util.bin.format.pdb2.pdbreader.PdbException;
import ghidra.app.util.bin.format.pdb2.pdbreader.PdbParser;
import ghidra.app.util.bin.format.pdb2.pdbreader.PdbReaderOptions;
import ghidra.app.util.pdb.PdbProgramAttributes;
import ghidra.net.http.HttpUtil;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.NotYetImplementedException;
import ghidra.util.task.TaskMonitor;
import net.jubjubnest.minidump.shared.ModuleData;

public class PdbResolver {

	public static PdbProgramAttributes getAttributes(Program program, Address moduleBase) throws MemoryAccessException, IOException {

		boolean analyzed = program.getOptions(Program.PROGRAM_INFO).getBoolean(Program.ANALYZED, false);
		ModuleParser.PdbInfo pdbInfo = ModuleParser.getPdbInfo(program, moduleBase);
		if (pdbInfo == null) {
			return null;
		}

		PdbProgramAttributes pdbAttributes = new PdbProgramAttributes(
				pdbInfo.guid, Integer.toString(pdbInfo.age),
				false, analyzed, null, pdbInfo.pdbName, "RSDS");

		return pdbAttributes;
	}

	public static class PdbResult {
		public File file;
		public AbstractPdb pdb;
	}

	public static PdbResult locatePdb(PdbProgramAttributes pdbAttributes, TaskMonitor monitor)
			throws MemoryAccessException, IOException, CancelledException, PdbException {

		if (pdbAttributes.getPdbFile() != null) {
			File candidate = new File(pdbAttributes.getPdbFile());
			PdbResult result = validatePdbCandidate(candidate, true, pdbAttributes, monitor);
			if (result != null) {
				return result;
			}
		}
		
		PdbResolver.SymbolPath symbolPath = PdbResolver.parseSymbolPath(
				"srv*C:\\symbols*\\\\localhost\\NetworkSymCache*https://msdl.microsoft.com/download/symbols");
		File symbolServerMatch = PdbResolver.loadSymbols(symbolPath, pdbAttributes);
		if (symbolServerMatch != null) {
			PdbResult result = new PdbResult();
			result.file = symbolServerMatch;
			result.pdb = PdbParser.parse(symbolServerMatch.getAbsolutePath(), new PdbReaderOptions(), monitor);
			return result;
		}

		return null;
	}

	public static PdbResult validatePdbCandidate(File candidate, boolean verifyGuidAge, PdbProgramAttributes pdbAttributes, TaskMonitor monitor) throws CancelledException, IOException, PdbException {

		if (candidate == null || !candidate.exists()) {
			return null;
		}

		AbstractPdb pdb = PdbParser.parse(candidate.getAbsolutePath(), new PdbReaderOptions(), monitor);
		if (verifyGuidAge) {
			if (!pdbAttributes.getPdbGuid().equals(pdb.getGuid().toString())) {
				return null;
			}

			if (!pdbAttributes.getPdbAge().equals(Integer.toHexString(pdb.getAge()))) {
				return null;
			}
		}
		
		PdbResult result = new PdbResult();
		result.file = candidate;
		result.pdb = pdb;
		return result;
	}
	
	public static class SymbolServerResult {
		File file;
		String path;
	}
	
	public static File loadSymbols(SymbolPath path, PdbProgramAttributes pdbAttributes) throws IOException {

		for (SymbolPathItem item : path.items) {
			switch (item.type) { 
			case SymbolServer:
				String[] servers = item.path.split("\\*");
				return loadSymbolsFromSymbolServers(servers, pdbAttributes);
			default:
				throw new NotYetImplementedException();
			}
		}
		
		return null;
	}

	private static File loadSymbolsFromSymbolServers(String[] servers, PdbProgramAttributes pdbAttributes) throws IOException {
		List<String> cascadeServers = new ArrayList<>();
		SymbolServerResult result = null;
		String tempPath = null;
		for (String server : servers) {

			result = loadSymbolsFromSymbolServer(server, tempPath, pdbAttributes);

			if (result != null) break;
			cascadeServers.add(server);

			// Use the previous physical server as the temp path to avoid having to make a temporary copy of possible downloads.
			// We'll end up extracting the files anyway later.
			if (!server.startsWith("http:") && !server.startsWith("https:")) {
				tempPath = server;
			}
		}
		
		if (result == null) {
			return null;
		}
		
		for (String cascade : cascadeServers) {
			File cascadedFile = new File(cascade, result.path);
			if (!cascadedFile.exists()) {
				cascadedFile.getParentFile().mkdirs();
				Files.copy(result.file, cascadedFile);
			}
		}
		
		return result.file;
	}

	private static SymbolServerResult loadSymbolsFromSymbolServer(String server, String tempPath, PdbProgramAttributes pdbAttributes) throws IOException {

		for (String candidate : pdbAttributes.getPotentialPdbFilenames()) {
			return loadSymbolsFromSymbolServerForCandidate(server, candidate, tempPath, pdbAttributes);
		}
		
		return null;
	}
	
	private static SymbolServerResult loadSymbolsFromSymbolServerForCandidate(String server, String candidate, String tempPath, PdbProgramAttributes pdbAttributes) throws IOException {

		if (!server.endsWith("/")) {
			server += "/";
		}
		String path = candidate + "/" + pdbAttributes.getGuidAgeCombo() + "/" + candidate;
		
		if (server.startsWith("http:") || server.startsWith("https:")) {
			return downloadFile(server, path, tempPath);
		}

		File file = new File(server, path);
		if (file.exists()) {
			SymbolServerResult result = new SymbolServerResult();
			result.file = file;
			result.path = path;
			return result;
		}
		
		return null;
	}
	
	private static SymbolServerResult downloadFile(String server, String path, String target) throws IOException {
		if (target == null) {
			File tmp = File.createTempFile("symbol", "pdb");
			tmp.delete();
			tmp.mkdirs();
			target = tmp.getAbsolutePath() + "/";
		}
		
		File targetFile = new File(target, path);
		targetFile.getParentFile().mkdirs();

		String url = server + path;
		try {
			HttpUtil.getFile(url, null, true, targetFile);
		} catch (IOException e) {
			return null;
		}
		
		if (targetFile.exists()) {
			SymbolServerResult result = new SymbolServerResult();
			result.file = targetFile;
			result.path = path;
			return result;
		}
		return null;
	}
	
	public static SymbolPath parseSymbolPath(String path) {

		String currentCache = null;
		List<SymbolPathItem> items = new ArrayList<>();
		for (String segment : path.split(";")) {
			if (segment.toLowerCase().startsWith("cache*")) {
				currentCache = segment.substring("cache*".length());
				continue;
			}
			
			items.add(parseSegment(segment, currentCache));
		}
		
		return new SymbolPath(items);
	}
	
	private static SymbolPathItem parseSegment(String segment, String globalCache) {

		SymbolPathType type = SymbolPathType.Directory;
		if (segment.toLowerCase().startsWith("srv*")) {
			type = SymbolPathType.SymbolServer;
			segment = segment.substring("srv*".length());
		}
		
		SymbolPathItem item = new SymbolPathItem();
		item.path = segment;
		item.type = type;
		item.cache = globalCache;
		return item;
	}
	
	static class SymbolPath {
		public SymbolPath(List<SymbolPathItem> items) {
			this.items = items;
		}

		public List<SymbolPathItem> items;
	}
	
	static class SymbolPathItem {
		public String path;
		public SymbolPathType type;
		public String cache;
	}
	
	enum SymbolPathType { SymbolServer, Directory }
}
