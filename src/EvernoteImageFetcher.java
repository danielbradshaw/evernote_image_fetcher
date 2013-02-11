import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.THttpClient;

import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMNotFoundException;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.limits.Constants;
import com.evernote.edam.notestore.NoteFilter;
import com.evernote.edam.notestore.NoteList;
import com.evernote.edam.notestore.NoteStore;
import com.evernote.edam.type.Note;
import com.evernote.edam.type.Notebook;
import com.evernote.edam.type.Resource;
import com.evernote.edam.type.User;
import com.evernote.edam.userstore.AuthenticationResult;
import com.evernote.edam.userstore.UserStore;

/**
 * Main class for the Evernote Interview sample application. This application
 * syncs with a given account and downloads all the images associated with that account.
 * 
 * To compile (Unix):
 *   javac -d bin -classpath .:lib/libthrift.jar:lib/log4j-1.2.14.jar:lib/evernote-api-1.21.jar src/EvernoteImageFetcher.java
 *
 * To run:
 *   java -classpath bin:lib/libthrift.jar:lib/log4j-1.2.14.jar:lib/evernote-api-1.21.jar EvernoteImageFetcher dbevernote dbevernote
 * 
 * @author Daniel Bradshaw
 */
public class EvernoteImageFetcher 
{
	private static final String consumerKey = "flashpass";
	private static final String consumerSecret = "6c3a533235d47fbb";
  
	// To use the production servers, simply change "sandbox.evernote.com" to "www.evernote.com".
	private static final String evernoteHost = "sandbox.evernote.com";
	private static final String userStoreUrl = "https://" + evernoteHost + "/edam/user";

	// Change the User Agent to a string that describes your application, using 
	// the form company name/app name and version. Using a unique user agent string 
	// allows us to identify applications in our logs and provide you with better support. 
	private static final String userAgent = "Evernote/EvernoteImageFetcher (Java) " + 
											com.evernote.edam.userstore.Constants.EDAM_VERSION_MAJOR + "." + 
											com.evernote.edam.userstore.Constants.EDAM_VERSION_MINOR;

	private UserStore.Client userStore;
	private NoteStore.Client noteStore;
	private String authToken;
	
	/**
	 * Main method of the application, authenticates the user and begins the image 
	 * fetching process.
	 */
	public static void main(String[] args) throws Exception 
	{		
		if (args.length < 2) {
			System.err.println("Arguments:  <username> <password>");
			return;
		}

		EvernoteImageFetcher evernoteImageFetcher = new EvernoteImageFetcher();

		// Connect and authenticate to the Evernote server
		if (evernoteImageFetcher.intitialize(args[0], args[1])) 
		{
			// Fetch and persist all images for the given account
			evernoteImageFetcher.fetchAllImages();
		}
	}

	/**
	 * Intialize UserStore and NoteStore clients. During this step, we
	 * authenticate with the Evernote web service.
	 */
	private boolean intitialize(String username, String password) throws Exception 
	{
		// Set up the UserStore client. The Evernote UserStore allows you to
		// authenticate a user and access some information about their account.
		THttpClient userStoreTrans = new THttpClient(userStoreUrl);
		userStoreTrans.setCustomHeader("User-Agent", userAgent);
		TBinaryProtocol userStoreProt = new TBinaryProtocol(userStoreTrans);
		userStore = new UserStore.Client(userStoreProt, userStoreProt);

		// Check that we can talk to the server
		boolean versionOk = userStore.checkVersion("DanielBradshaw/EvernoteImageFetcher (Java)",
				com.evernote.edam.userstore.Constants.EDAM_VERSION_MAJOR,
				com.evernote.edam.userstore.Constants.EDAM_VERSION_MINOR);
		
		if (!versionOk) {
			System.err.println("Incomatible EDAM client protocol version");
			return false;
		}

		// Authenticate using username & password
		AuthenticationResult authResult = null;
		try {
			authResult = userStore.authenticate(username, password, consumerKey, consumerSecret);
		} 
		catch (EDAMUserException ex) 
		{
			// Note that the error handling here is far more detailed than you
			// would provide to a real user. It is intended to give you an idea of why
			// the sample application isn't able to authenticate to our servers.
			String parameter = ex.getParameter();
			EDAMErrorCode errorCode = ex.getErrorCode();

			System.err.println("Authentication failed (parameter: " + parameter
					+ " errorCode: " + errorCode + ")");

			if (errorCode == EDAMErrorCode.INVALID_AUTH) {
				if (parameter.equals("consumerKey")) {
					if (consumerKey.equals("en-edamtest")) {
						System.err
								.println("You must replace the variables consumerKey and consumerSecret with the values you received from Evernote.");
					} else {
						System.err
								.println("Your consumer key was not accepted by "
										+ evernoteHost);
						System.err
								.println("This sample client application requires a client API key. If you requested a web service API key, you must authenticate using OAuth as shown in sample/java/oauth");
					}
					System.err
							.println("If you do not have an API Key from Evernote, you can request one from http://dev.evernote.com/documentation/cloud/");
				} else if (parameter.equals("username")) {
					System.err
							.println("You must authenticate using a username and password from "
									+ evernoteHost);
					if (evernoteHost.equals("www.evernote.com") == false) {
						System.err
								.println("Note that your production Evernote account will not work on "
										+ evernoteHost + ",");
						System.err
								.println("you must register for a separate test account at https://"
										+ evernoteHost + "/Registration.action");
					}
				} else if (parameter.equals("password")) {
					System.err.println("The password that you entered is incorrect");
				}
			}

			return false;
		}

		// The result of a successful authentication is an opaque authentication
		// token that you will use in all subsequent API calls. If you are developing
		// a web application that authenticates using OAuth, the OAuth access
		// token that you receive would be used as the authToken in subsequent calls.
		authToken = authResult.getAuthenticationToken();

		// The Evernote NoteStore allows you to access user's notes.
		// In order to access the NoteStore for a given user, you need to know
		// the logical "shard" that their notes are stored on. The shard ID is
		// included in the URL used to access the NoteStore.
		User user = authResult.getUser();

		System.out.println("Successfully authenticated as " + user.getUsername());

		// Set up the NoteStore client
		THttpClient noteStoreTrans = new THttpClient(authResult.getNoteStoreUrl());
		noteStoreTrans.setCustomHeader("User-Agent", userAgent);
		TBinaryProtocol noteStoreProt = new TBinaryProtocol(noteStoreTrans);
		noteStore = new NoteStore.Client(noteStoreProt, noteStoreProt);

		return true;
	}
	
	/**
	 * Iterate through all notes for a given user's account, find all images associated with 
	 * those notes, and download those images to a local directory for storage.
	 */
	private void fetchAllImages() throws Exception 
	{
		// List all of the notes in the user's account
		System.out.println("Writing out the following list of images:");

		// First, get a list of all notebooks
		List<Notebook> notebooks = noteStore.listNotebooks(authToken);

		for (Notebook notebook : notebooks) 
		{
			System.out.println("Notebook: " + notebook.getName());

			// Create a default filter for the current notebook
			NoteFilter filter = new NoteFilter();
			filter.setNotebookGuid(notebook.getGuid());
			
			// Get all notes for the current notebook - in a mobile environment we likely will paginate
			// here and grab around 100 notes at a time, for example, to limit memory consumption
			NoteList noteList = noteStore.findNotes(authToken, filter, 0, Constants.EDAM_USER_NOTES_MAX);
			List<Note> notes = noteList.getNotes();
			for (Note note : notes) {
				if (note.getResourcesSize() > 0) {
					// If this note has resources, attempt to persist it
					persistImageResources(note.getResources());
				}
			}
		}
		
		System.out.println();
	}
	
	/**
	 * Find all image resources in the given resource list and persist images
	 * to disk.
	 * 
	 * @param resources collection of resources in which to persist images
	 */
	private void persistImageResources(List<Resource> resources)
	{
		// Ensure we have an output directory - for simplicity sake, it will be a subdirectory of the working dir
		File outputDirectory = new File(System.getProperty("user.dir") + "/evernote_images/");
		outputDirectory.mkdir();

		// Iterate through set of resources, finding those
		// that have an image mimetype and can be written to file
		for (Resource resource : resources) 
		{
			if (resource.isSetMime()) 
			{
				if (resource.getMime().equalsIgnoreCase(Constants.EDAM_MIME_TYPE_GIF) || 
					resource.getMime().equalsIgnoreCase(Constants.EDAM_MIME_TYPE_JPEG) ||
					resource.getMime().equalsIgnoreCase(Constants.EDAM_MIME_TYPE_PNG)) 
				{
					System.out.println(" * " + "Found an image! Filename: " + resource.getAttributes().getFileName());
					
					try {
						// Get the image through the NoteStore - in a mobile environment we would more likely use
						// an HTTP POST to the Evernote resource URL to avoid reading the data into memory. Preferably,
						// we can stream the image data directly to a file. As a side note, this block of code would likely
						// be on a background thread in a production environment for better UI handling.
						byte[] imageBytes = noteStore.getResourceData(authToken, resource.getGuid());
						
						// Use the GUID as part of the filename to avoid clashes - again in production environment and depending
						// on how unique filenames are in a given scope, a subdirectory for each notebook may work
						String outputPath = outputDirectory.getAbsolutePath() + "/" + resource.getGuid() + "_" + resource.getAttributes().getFileName();
						FileOutputStream fileOutputStream = new FileOutputStream(outputPath);
						fileOutputStream.write(imageBytes);
						fileOutputStream.close();
						
						System.out.println("   Image '" + resource.getAttributes().getFileName() + "' successfully written.");

					} catch (IOException e) {
						System.out.println("IOException: " + e.getLocalizedMessage());
					} catch (EDAMUserException e) {
						System.out.println("EDAMUserException: " + e.getLocalizedMessage());
					} catch (EDAMSystemException e) {
						System.out.println("EDAMSystemException: " + e.getLocalizedMessage());
					} catch (EDAMNotFoundException e) {
						System.out.println("EDAMNotFoundException: " + e.getLocalizedMessage());
					} catch (TException e) {
						System.out.println("TException: " + e.getLocalizedMessage());
					}
				}
			}
		}
	}
}
