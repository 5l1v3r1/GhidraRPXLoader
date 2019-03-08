package de.mas.ghidra.wiiu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import de.mas.ghidra.utils.Utils;
import generic.continues.RethrowContinuesFactory;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.bin.format.elf.ElfConstants;
import ghidra.app.util.bin.format.elf.ElfException;
import ghidra.app.util.bin.format.elf.ElfHeader;
import ghidra.app.util.bin.format.elf.ElfSectionHeader;
import ghidra.app.util.bin.format.elf.ElfSectionHeaderConstants;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

public class RPXUtils {
	private static byte[] RPX_MAGIC = new byte[] { 0x7F, 0x45, 0x4C, 0x46, 0x01, 0x02, 0x01, (byte) 0xCA, (byte) 0xFE };
	public static final int SHF_RPL_ZLIB = 0x08000000;
	public static final int SHT_NOBITS = 0x00000008;

	public static final int SHT_RPL_EXPORTS = 0x80000001;
	public static final int SHT_RPL_IMPORTS = 0x80000002;
	public static final int SHT_RPL_CRCS = 0x80000003;
	public static final int SHT_RPL_FILEINFO = 0x80000004;

	public static byte[] convertRPX(ByteProvider bProvider, TaskMonitor monitor)
			throws ElfException, IOException, CancelledException, DataFormatException {
		ElfHeader elfFile = ElfHeader.createElfHeader(RethrowContinuesFactory.INSTANCE, bProvider);
		elfFile.parse();

		ByteBuffer buffer = ByteBuffer.allocate(0);

		long shdr_elf_offset = elfFile.e_ehsize() & 0xFFFFFFFF;
		long shdr_data_elf_offset = shdr_elf_offset + elfFile.e_shnum() * elfFile.e_shentsize();

		for (ElfSectionHeader h : elfFile.getSections()) {
			monitor.checkCanceled();
			long curSize = h.getSize();
			long flags = h.getFlags();
			long offset = h.getOffset();

			if (offset != 0) {
				if ((flags & SHT_NOBITS) != SHT_NOBITS) {
					byte[] data = h.getData();

					if ((flags & SHF_RPL_ZLIB) == SHF_RPL_ZLIB) {
						monitor.setMessage("Decompressing section " + h.getTypeAsString());
						long section_size_inflated = ByteBuffer.wrap(Arrays.copyOf(data, 4)).getInt() & 0xFFFFFFFF;
						Inflater inflater = new Inflater();
						inflater.setInput(data, 4, (int) h.getSize() - 4); // the first byte is the size

						byte[] decompressed = new byte[(int) section_size_inflated];

						inflater.inflate(decompressed);

						inflater.end();

						// Is this alignment really necessary?
						curSize = (section_size_inflated + 0x03) & ~0x3;
						flags &= ~SHF_RPL_ZLIB;
						data = decompressed;
					}
					long newEnd = shdr_data_elf_offset + curSize;

					buffer = Utils.checkAndGrowByteBuffer(buffer, newEnd);
					buffer.position((int) shdr_data_elf_offset);
					// System.out.println("Write data " + String.format("%08X",
					// shdr_data_elf_offset));
					buffer.put(data);
					offset = shdr_data_elf_offset;
					shdr_data_elf_offset += curSize;
				}
			}

			// Hacky way to fix import relocations
			if (h.getType() == ElfSectionHeaderConstants.SHT_SYMTAB) {
				monitor.setMessage("Fix import relocations " + h.getTypeAsString());
				int symbolCount = (int) ((int) (curSize) / h.getEntrySize());
				long entryPos = 0;
				for (int i = 0; i < symbolCount; i++) {
					monitor.checkCanceled();
					long test_offset = (int) (offset + entryPos + 4);
					buffer.position((int) test_offset);
					int val = buffer.getInt();

					if ((val & 0xF0000000L) == 0xC0000000L) {
						long fixedAddress = val - 0xC0000000L + 0x01000000L;
						buffer.position((int) test_offset);
						buffer.putInt((int) fixedAddress);
					}
					entryPos += h.getEntrySize();
				}
			}

			buffer = Utils.checkAndGrowByteBuffer(buffer, shdr_elf_offset + 0x28);

			monitor.setMessage("Converting section " + h.getTypeAsString());

			buffer.position((int) shdr_elf_offset);
			System.out.println("Write header " + String.format("%08X", shdr_elf_offset));
			buffer.putInt(h.getName());
			if (h.getType() == SHT_RPL_CRCS || h.getType() == SHT_RPL_FILEINFO || h.getType() == SHT_RPL_EXPORTS
					|| h.getType() == SHT_RPL_IMPORTS) {
				buffer.putInt(ElfSectionHeaderConstants.SHT_NULL);
			} else {
				buffer.putInt(h.getType());
			}
			buffer.putInt((int) flags);

			// Hacky way to fix import relocations
			if ((h.getAddress() & 0xF0000000L) == 0xC0000000L) {
				long fixedAddress = h.getAddress() - 0xC0000000L + 0x01000000L;
				buffer.putInt((int) fixedAddress);
			} else {
				buffer.putInt((int) h.getAddress());
			}

			buffer.putInt((int) offset);
			buffer.putInt((int) curSize);
			buffer.putInt(h.getLink());
			buffer.putInt(h.getInfo());
			buffer.putInt((int) h.getAddressAlignment());
			buffer.putInt((int) h.getEntrySize());

			shdr_elf_offset += 0x28;
		}

		monitor.setMessage("Create new ELF header");

		buffer = Utils.checkAndGrowByteBuffer(buffer, 36);

		buffer.position(0);
		buffer.put(RPX_MAGIC);
		buffer.position(0x10);
		buffer.putShort(ElfConstants.ET_EXEC); // e.e_type());
		buffer.putShort(elfFile.e_machine());
		buffer.putInt(elfFile.e_version());
		buffer.putInt((int) elfFile.e_entry());
		buffer.putInt((int) elfFile.e_phoff());
		buffer.putInt(elfFile.e_ehsize()); // e.e_shoff());
		buffer.putInt(elfFile.e_flags());
		buffer.putShort(elfFile.e_ehsize());
		buffer.putShort(elfFile.e_phentsize());
		buffer.putShort(elfFile.e_phnum());
		buffer.putShort(elfFile.e_shentsize());
		buffer.putShort(elfFile.e_shnum());
		buffer.putShort(elfFile.e_shstrndx());

		return buffer.array();
	}
}
