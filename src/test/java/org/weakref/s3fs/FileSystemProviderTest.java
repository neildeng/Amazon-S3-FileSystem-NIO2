package org.weakref.s3fs;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.weakref.s3fs.S3Path.forPath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.weakref.s3fs.util.AmazonS3ClientMockAlternative;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import com.google.common.collect.ImmutableMap;

public class FileSystemProviderTest {
	S3FileSystemProvider provider;
	FileSystem fsMem;
	
	@Before
	public void cleanup() throws IOException{
		fsMem = MemoryFileSystemBuilder.newLinux().build("basescheme");
		try{
			FileSystems.getFileSystem(URI.create("s3:///")).close();
		}
		catch(FileSystemNotFoundException e){}
		
		//S3FileSystem fs = spy(new S3FileSystem(provider, client, endpoint))
		provider = spy(new S3FileSystemProvider());
		doReturn(new Properties()).when(provider).loadAmazonProperties();
	}
	
	@After
	public void closeMemory() throws IOException{
		fsMem.close();
	}
	
	
	private void mockFileSystem(final Path memoryBucket){
		try {
			AmazonS3ClientMockAlternative clientMock = new AmazonS3ClientMockAlternative(memoryBucket);
			S3FileSystem s3ileS3FileSystem = new S3FileSystem(provider, clientMock, "endpoint");
			doReturn(s3ileS3FileSystem).when(provider).createFileSystem(any(URI.class), anyObject(), anyObject());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Test
	public void createsAuthenticatedByEnv() throws IOException {
		
		Map<String, ?> env = buildFakeEnv();
		URI uri = URI.create("s3:///");
		
		FileSystem fileSystem = provider.newFileSystem(uri,
				env);

		assertNotNull(fileSystem);	
		verify(provider).createFileSystem(eq(uri),eq(env.get(S3FileSystemProvider.ACCESS_KEY)), eq(env.get(S3FileSystemProvider.SECRET_KEY)));
	}
	
	@Test
	public void createAuthenticatedByProperties() throws IOException{
		Properties props = new Properties();
		props.setProperty(S3FileSystemProvider.SECRET_KEY, "secret key");
		props.setProperty(S3FileSystemProvider.ACCESS_KEY, "access key");
		doReturn(props).when(provider).loadAmazonProperties();
		URI uri = URI.create("s3:///");
		
		FileSystem fileSystem = provider.newFileSystem(uri, ImmutableMap.<String, Object> of());
		assertNotNull(fileSystem);
		
		verify(provider).createFileSystem(eq(uri), eq("access key"), eq("secret key"));
	}

	@Test
	public void createsAnonymous() throws IOException {
		URI uri = URI.create("s3:///");
		FileSystem fileSystem = provider.newFileSystem(uri, ImmutableMap.<String, Object> of());

		assertNotNull(fileSystem);
		verify(provider).createFileSystem(eq(uri),eq(null),eq(null));
	}

	@Test(expected = FileSystemAlreadyExistsException.class)
	public void createFailsIfAlreadyCreated() throws IOException {
		FileSystem fileSystem = provider.newFileSystem(URI.create("s3:///"),
				ImmutableMap.<String, Object> of());
		assertNotNull(fileSystem);

		provider.newFileSystem(URI.create("s3:///"),
				ImmutableMap.<String, Object> of());
	}

	@Test
	public void getFileSystem() throws IOException {
		FileSystem fileSystem = provider.newFileSystem(URI.create("s3:///"),
				ImmutableMap.<String, Object> of());
		assertNotNull(fileSystem);

		FileSystem other = provider.getFileSystem(URI.create("s3:///"));
		assertSame(fileSystem, other);
	}

	@Test(expected = FileSystemNotFoundException.class)
	public void getFailsIfNotYetCreated() {
		provider.getFileSystem(URI.create("s3:///"));
	}

	@Test
	public void getPathWithEmtpyEndpoint() throws IOException {
		FileSystem fs = FileSystems.newFileSystem(URI.create("s3:///"),
				ImmutableMap.<String, Object> of());
		Path path = fs.provider().getPath(URI.create("s3:///bucket/path/to/file"));

		assertEquals(path, forPath("/bucket/path/to/file"));
		assertSame(path.getFileSystem(), fs);
	}
	
	@Test
	public void getPath() throws IOException {
		FileSystem fs = FileSystems.newFileSystem(URI.create("s3://endpoint1/"),
				ImmutableMap.<String, Object> of());
		Path path = fs.provider().getPath(URI.create("s3:///bucket/path/to/file"));

		assertEquals(path, forPath("/bucket/path/to/file"));
		assertSame(path.getFileSystem(), fs);
	}

	@Test
	public void getAnotherPath() throws IOException {
		FileSystem fs = FileSystems.newFileSystem(URI.create("s3://endpoint1/"),
				ImmutableMap.<String, Object> of());
		Path path = fs.provider().getPath(URI.create("s3://endpoint1/bucket/path/to/file"));

		assertEquals(path, forPath("/bucket/path/to/file"));
		assertSame(path.getFileSystem(), fs);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getPathWithInvalidEndpoint () throws IOException {
		FileSystem fs = FileSystems.newFileSystem(URI.create("s3://endpoint1/"),
				ImmutableMap.<String, Object> of());
		fs.provider().getPath(URI.create("s3://endpoint2/bucket/path/to/file"));
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getPathWithEndpointAndWithoutBucket() throws IOException {
		FileSystem fs = FileSystems.newFileSystem(URI.create("s3://endpoint1/"),
				ImmutableMap.<String, Object> of());
		fs.provider().getPath(URI.create("s3://endpoint1//falta-bucket"));
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void getPathWithDefaultEndpointAndWithoutBucket() throws IOException {
		FileSystem fs = FileSystems.newFileSystem(URI.create("s3:///"),
				ImmutableMap.<String, Object> of());
		fs.provider().getPath(URI.create("s3:////falta-bucket"));
	}

	@Test
	public void closeFileSystemReturnNewFileSystem() throws IOException {
		S3FileSystemProvider provider = new S3FileSystemProvider();
		Map<String, ?> env = buildFakeEnv();

		FileSystem fileSystem = provider.newFileSystem(URI.create("s3:///"),
				env);
		assertNotNull(fileSystem);

		fileSystem.close();

		FileSystem fileSystem2 = provider.newFileSystem(URI.create("s3:///"),
				env);

		assertNotSame(fileSystem, fileSystem2);
	}

	@Test(expected = FileSystemAlreadyExistsException.class)
	public void createTwoFileSystemThrowError() throws IOException {
		S3FileSystemProvider provider = new S3FileSystemProvider();
		Map<String, ?> env = buildFakeEnv();

		FileSystem fileSystem = provider.newFileSystem(URI.create("s3:///"),
				env);
		assertNotNull(fileSystem);
		provider.newFileSystem(URI.create("s3:///"), env);

	}
	
	// stream directory
	
	@Test
	public void createStreamDirectoryReader() throws IOException{
		
		// fixtures
		Path bucketA = Files.createDirectories(fsMem.getPath("/base", "bucketA"));
		Files.createFile(bucketA.resolve("file1"));
		
		mockFileSystem(fsMem.getPath("/base"));
		
		Path bucket = provider.newFileSystem(URI.create("s3://endpoint1/"), buildFakeEnv()).getPath("/bucketA");
		// assert
		provider.newDirectoryStream(bucket, new  DirectoryStream.Filter<Path>(){
			@Override public boolean accept(Path entry) throws IOException {
				assertEquals("file1", entry.getFileName().toString());
				return true;
			}
		});
	}
	
	@Test
	public void createAnotherStreamDirectoryReader() throws IOException{
		
		// fixtures
		Path bucketA = Files.createDirectories(fsMem.getPath("/base", "bucketA"));
		Files.createFile(bucketA.resolve("file1"));
		Files.createFile(bucketA.resolve("file2"));
		
		mockFileSystem(fsMem.getPath("/base"));
		Path bucket = provider.newFileSystem(URI.create("s3://endpoint1/"), buildFakeEnv()).getPath("/bucketA");

		// assert
		provider.newDirectoryStream(bucket, new  DirectoryStream.Filter<Path>(){
			@Override public boolean accept(Path entry) throws IOException {
				String fileName = entry.getFileName().toString();
				assertTrue(fileName.equals("file1") || fileName.equals("file2"));
				return true;
			}
		});
	}
	
	@Test
	public void createAnotherWithDirStreamDirectoryReader() throws IOException{
		
		// fixtures
		Path bucketA = Files.createDirectories(fsMem.getPath("/base", "bucketA"));
		Files.createFile(bucketA.resolve("file1"));
		Files.createDirectory(bucketA.resolve("dir1"));
		
		mockFileSystem(fsMem.getPath("/base"));
		Path bucket = provider.newFileSystem(URI.create("s3://endpoint1/"), buildFakeEnv()).getPath("/bucketA");

		// assert
		provider.newDirectoryStream(bucket, new  DirectoryStream.Filter<Path>(){
			@Override public boolean accept(Path entry) throws IOException {
				String fileName = entry.getFileName().toString();
				assertTrue(fileName.equals("file1") || fileName.equals("dir1"));
				return true;
			}
		});
	}
	
	// newInputStream
	
	@Test
	public void inputStreamFile() throws IOException{
		
		// fixtures
		byte[] res = "contenido".getBytes();
		Path bucketA = Files.createDirectories(fsMem.getPath("/base", "bucketA"));
		Files.write(bucketA.resolve("file1"), res, StandardOpenOption.CREATE_NEW);
		
		mockFileSystem(fsMem.getPath("/base"));
		Path file = provider.newFileSystem(URI.create("s3://endpoint1/"), buildFakeEnv()).getPath("/bucketA/file1");
		
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (InputStream input = provider.newInputStream(file)){
			int nRead;
			byte[] data = new byte[16384];
			while ((nRead = input.read(data, 0, data.length)) != -1) {
			  buffer.write(data, 0, nRead);
			}
			buffer.flush();
		}
		// check
		assertArrayEquals(res, buffer.toByteArray());
	}
	
	@Test
	public void anotherInputStreamFile() throws IOException{
		// fixtures
		byte[] res = "contenido diferente".getBytes();
		Path dir = Files.createDirectories(fsMem.getPath("/base", "bucketA", "dir"));
		Files.write(dir.resolve("file1"), res, StandardOpenOption.CREATE_NEW);
		
		mockFileSystem(fsMem.getPath("/base"));
		Path file = provider.newFileSystem(URI.create("s3://endpoint1/"), buildFakeEnv()).getPath("/bucketA/dir/file1");
		
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (InputStream input = provider.newInputStream(file)){
			int nRead;
			byte[] data = new byte[16384];
			while ((nRead = input.read(data, 0, data.length)) != -1) {
			  buffer.write(data, 0, nRead);
			}
			buffer.flush();
		}
		// check
		assertArrayEquals(res, buffer.toByteArray());
	}
	
	@Test(expected = IOException.class)
	public void inputStreamDirectory() throws IOException{
		// fixtures
		Files.createDirectories(fsMem.getPath("/base", "bucketA", "dir"));
		mockFileSystem(fsMem.getPath("/base"));
		Path result = provider.newFileSystem(URI.create("s3://endpoint1/"), buildFakeEnv()).getPath("/bucketA/dir");
		// act
		provider.newInputStream(result);
	}
	
	// newOutputStream 
	
	
	
	private Map<String, ?> buildFakeEnv(){
		return ImmutableMap.<String, Object> builder()
				.put(S3FileSystemProvider.ACCESS_KEY, "access key")
				.put(S3FileSystemProvider.SECRET_KEY, "secret key").build();
	}
}