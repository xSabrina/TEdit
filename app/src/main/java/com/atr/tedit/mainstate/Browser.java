/*
 * Free Public License 1.0.0
 * Permission to use, copy, modify, and/or distribute this software
 * for any purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL
 * WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL
 * THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR
 * CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
 * LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
 * CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package com.atr.tedit.mainstate;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.ListFragment;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.provider.DocumentFile;
import android.support.v7.app.AlertDialog;
import android.support.v7.view.ContextThemeWrapper;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.atr.tedit.R;
import com.atr.tedit.TEditActivity;
import com.atr.tedit.dialog.PossibleBinary;
import com.atr.tedit.dialog.TDialog;
import com.atr.tedit.dialog.VolumePicker;
import com.atr.tedit.file.AndPath;
import com.atr.tedit.file.descriptor.AndFile;
import com.atr.tedit.settings.Settings;
import com.atr.tedit.settings.TxtSettings;
import com.atr.tedit.util.AndFileFilter;
import com.atr.tedit.util.DataAccessUtil;
import com.atr.tedit.dialog.ErrorMessage;
import com.atr.tedit.util.FontUtil;
import com.atr.tedit.util.SettingsApplicable;
import com.atr.tedit.util.TEditDB;
import com.atr.tedit.utilitybar.UtilityBar;

import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * @author Adam T. Ryder
 * <a href="http://1337atr.weebly.com">http://1337atr.weebly.com</a>
 */

public class Browser extends ListFragment implements SettingsApplicable {
    private static final String invalidChars = "\'/\\*?:|\"<>%\n";

    public static final int TYPE_OPEN = 0;
    public static final int TYPE_SAVE = 1;

    private int type;
    private TEditActivity ctx;

    private AndPath currentPath;

    private int numDirs;
    private int numFiles;

    private long keyToSave;

    public static Browser newInstance(String path, long key) {
        Bundle bundle = new Bundle();
        bundle.putInt("TEditBrowser.type", TYPE_SAVE);
        bundle.putString("TEditBrowser.currentPath", path);
        bundle.putLong("TEditBrowser.keyToSave", key);

        Browser browser = new Browser();
        browser.setArguments(bundle);

        return browser;
    }

    public static Browser newInstance(String path) {
        Bundle bundle = new Bundle();
        bundle.putInt("TEditBrowser.type", TYPE_OPEN);
        bundle.putString("TEditBrowser.currentPath", path);

        Browser browser = new Browser();
        browser.setArguments(bundle);

        return browser;
    }

    public int getType() {
        return type;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        ctx = (TEditActivity)context;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            Bundle bundle = getArguments();
            type = bundle.getInt("TEditBrowser.type", TYPE_OPEN);
            if (type == TYPE_SAVE)
                keyToSave = bundle.getLong("TEditBrowser.keyToSave", -1);

            String path = bundle.getString("TEditBrowser.currentPath", "");
            if (path.isEmpty()) {
                currentPath = ctx.getCurrentPath().clone();
                return;
            }

            AndPath tmpPath = null;
            try {
                tmpPath = AndPath.fromJson(ctx, path);
            } catch (Exception e) {
                tmpPath = null;
            }

            currentPath = tmpPath == null ? ctx.getCurrentPath().clone() : tmpPath;
            return;
        }

        type = savedInstanceState.getInt("TEditBrowser.type", TYPE_OPEN);
        if (type == TYPE_SAVE)
            keyToSave = savedInstanceState.getLong("TEditBrowser.keyToSave", -1);

        String path = savedInstanceState.getString("TEditBrowser.currentPath", "");
        if (path.isEmpty()) {
            currentPath = ctx.getCurrentPath().clone();
            return;
        }

        AndPath tmpPath = null;
        try {
            tmpPath = AndPath.fromJson(ctx, path);
        } catch (Exception e) {
            tmpPath = null;
            Log.i("TEdit", e.getMessage());
        }

        currentPath = tmpPath == null ? ctx.getCurrentPath().clone() : tmpPath;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (type == TYPE_OPEN)
            return inflater.inflate(R.layout.browser, container, false);

        return inflater.inflate(R.layout.browser_save, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        registerForContextMenu(getListView());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("TEditBrowser.currentPath", currentPath.toJson());
        outState.putInt("TEditBrowser.type", type);
        outState.putLong("TEditBrowser.keyToSave", keyToSave);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (ctx.getUtilityBar().getState().STATE == UtilityBar.STATE_BROWSE) {
            ctx.getUtilityBar().getState().setEnabled(false);
            ctx.getUtilityBar().handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    ctx.getUtilityBar().getState().setEnabled(true);
                }
            }, TEditActivity.SWAP_ANIM_LENGTH);
        } else
            ctx.getUtilityBar().setToBrowser();

        getListView().setEnabled(true);
        applySettings();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (type == TYPE_SAVE) {
            getView().findViewById(R.id.filename).setEnabled(false);
        }
        getListView().setEnabled(false);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, view, menuInfo);
        if (ctx.getSettingsWindow().isOpen())
            return;

        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;

        if (info.position >= numDirs) {
            menu.add(ContextMenu.NONE, 0, ContextMenu.NONE, R.string.delete);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AndFile file = (AndFile)getListAdapter()
                .getItem(((AdapterView.AdapterContextMenuInfo)item.getMenuInfo()).position);

        if (item.getItemId() == 0) {
            if (file.isDirectory()) {
                Log.w("TEdit", "Attempt to delete directory: " + file.getPath());
                return true;
            }

            DeleteDialog dd = DeleteDialog.newInstance(file.getPathIdentifier());
            dd.show(ctx.getSupportFragmentManager(), "DeleteDialog");

            return true;
        }

        return super.onContextItemSelected(item);
    }

    @Override
    public void onListItemClick(ListView listView, View view, int position, long id) {
        super.onListItemClick(listView, view, position, id);

        AndFile file = (AndFile)listView.getAdapter().getItem(position);
        if (position < numDirs) {
            if (file.exists()) {
                if (!currentPath.moveToChild(file)) {
                    ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                            getString(R.string.missing_dir));
                    em.show(ctx.getSupportFragmentManager(), "dialog");
                    return;
                }
                populateBrowser();
                return;
            }

            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                    getString(R.string.missing_dir));
            em.show(ctx.getSupportFragmentManager(), "dialog");

            return;
        }

        if (type == TYPE_SAVE) {
            ((EditText)getView().findViewById(R.id.savelayout).findViewById(R.id.filename)).setText(file.getName());
            return;
        }

        openFile(file, false);
    }

    public void openFile(AndFile file, boolean skipBinaryCheck) {
        if (file == null || !file.exists()) {
            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                    getString(R.string.missing_file));
            em.show(ctx.getSupportFragmentManager(), "dialog");

            return;
        }

        if (!file.canRead()) {
            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),getString(R.string.error_readfile));
            em.show(ctx.getSupportFragmentManager(), "dialog");

            return;
        }

        if (!skipBinaryCheck && DataAccessUtil.probablyBinaryFile(file, ctx)) {
            PossibleBinary pBin = PossibleBinary.getInstance(file.getPathIdentifier());
            pBin.show(ctx.getSupportFragmentManager(), "alert");
            return;
        }

        String contents = null;
        try {
            contents = DataAccessUtil.readFile(file, ctx);
        } catch (IOException e) {
            contents = null;
            Log.e("TEdit.Browser", "Unable to read file " + file.getPath() + ": "
                    + e.getMessage());
            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                    getString(R.string.error_readfile));
            em.show(ctx.getSupportFragmentManager(), "dialog");

            return;
        } finally {
            if (contents == null)
                return;

            ctx.setCurrentPath(currentPath);
            ctx.newDocument(file.getPathIdentifier(), contents);
        }
    }

    public void upDir() {
        if (currentPath.moveToParent() == null)
            return;
        populateBrowser();
    }

    public String getEnteredFilename() {
        return ((EditText)getView().findViewById(R.id.savelayout).findViewById(R.id.filename)).getText().toString();
    }

    public AndPath getCurrentPath() {
        return currentPath;
    }

    private void populateBrowser() {
        if (!currentPath.getCurrent().exists()) {
            while(currentPath.moveToParent() != null && !currentPath.getCurrent().exists())
                continue;

            if (!currentPath.getCurrent().exists()) {
                ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                        getString(R.string.missing_dir));
                em.show(ctx.getSupportFragmentManager(), "dialog");
                return;
            }
        }

        final TextView pathView = type == TYPE_OPEN ? (TextView)getView().findViewById(R.id.browsepath)
                : (TextView)getView().findViewById(R.id.savebrowsepath);
        pathView.setText(currentPath.getPath());
        if (Settings.getSystemTextDirection() == Settings.TEXTDIR_RTL) {
            ((HorizontalScrollView)pathView.getParent()).fullScroll(View.FOCUS_LEFT);
        } else
            ((HorizontalScrollView)pathView.getParent()).fullScroll(View.FOCUS_RIGHT);

        AndFile[] dirList = currentPath.listFiles(new DirFilter());
        AndFile[] fileList = currentPath.listFiles(new TxtFilter());
        numDirs = dirList.length;
        numFiles = fileList.length;

        Comparator<AndFile> comparator = new Comparator<AndFile>() {
            @Override
            public int compare(final AndFile o1, final AndFile o2) {
                return o1.getName().compareToIgnoreCase(o2.getName());
            }
        };
        Arrays.sort(dirList, comparator);
        Arrays.sort(fileList, comparator);

        ArrayList<AndFile> items = new ArrayList<>(fileList.length + dirList.length);
        for (AndFile f : dirList) {
            items.add(f);
        }
        for (AndFile f : fileList) {
            items.add(f);
        }

        setListAdapter(new ArrayAdapter<AndFile>(ctx, (Settings.getSystemTextDirection() == Settings.TEXTDIR_LTR) ?
                R.layout.browser_row : R.layout.browser_row_rtl, items) {

            @Override
            public int getCount() { return super.getCount(); }

            @Override
            public View getView(int position, View view, ViewGroup parent) {
                View row = view;
                ImageView iv;
                TextView tv;
                if (row == null) {
                    row = ((Activity) getContext()).getLayoutInflater().inflate((Settings.getSystemTextDirection()
                                    == Settings.TEXTDIR_LTR) ? R.layout.browser_row : R.layout.browser_row_rtl,
                                    parent, false);
                    iv = row.findViewById(R.id.dirIcon);
                    tv = row.findViewById(R.id.dirText);

                    tv.setTypeface(FontUtil.getSystemTypeface());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        tv.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
                        tv.setTextDirection((Settings.getSystemTextDirection() == Settings.TEXTDIR_LTR) ?
                                View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
                    }
                } else {
                    iv = row.findViewById(R.id.dirIcon);
                    tv = row.findViewById(R.id.dirText);
                }
                AndFile item = getItem(position);

                iv.setImageResource(item.isDirectory() ? R.drawable.dir_focused : R.drawable.doc_focused);
                tv.setText(item.getName());

                return row;
            }
        });
    }

    private static boolean isValidName (String name) {
        if (name.length() == 0) {
            return false;
        } else {
            for (int count = 0; count < invalidChars.length(); count++) {
                if (name.indexOf(invalidChars.charAt(count)) > -1) {
                    return false;
                }
            }
            return true;
        }
    }

    public AndFile saveFile(String filename, final String body) {
        if (!currentPath.getCurrent().exists()) {
            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                    getString(R.string.missing_dir));
            em.show(ctx.getSupportFragmentManager(), "dialog");

            return null;
        }

        if (!isValidName(filename)) {
            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                    getString(R.string.error_invalidname));
            em.show(ctx.getSupportFragmentManager(), "dialog");

            return null;
        }

        if (currentPath.getCurrent().getType() == AndFile.TYPE_FILE) {
            File lFile = new File(currentPath.getPath(), filename);

            if (lFile.exists()) {
                //prompt overwrite
                Bundle bundle = new Bundle();
                bundle.putString("Overwrite.filePath", currentPath.toJson());
                bundle.putString("Overwrite.fileName", filename);
                bundle.putString("Overwrite.body", body);

                OverwriteDialog od = new OverwriteDialog();
                od.setArguments(bundle);
                od.show(ctx.getSupportFragmentManager(), "Overwrite");

                return null;
            }
        } else {
            AndFile[] files = currentPath.listFiles();
            AndFile owFile = null;
            for (AndFile f : files) {
                if (f.isFile() && f.getName().equals(filename)) {
                    owFile = f;
                    break;
                }
            }

            if (owFile != null) {
                //prompt overwrite
                Bundle bundle = new Bundle();
                bundle.putString("Overwrite.filePath", currentPath.toJson());
                bundle.putString("Overwrite.fileName", filename);
                bundle.putString("Overwrite.body", body);

                OverwriteDialog od = new OverwriteDialog();
                od.setArguments(bundle);
                od.show(ctx.getSupportFragmentManager(), "Overwrite");

                return null;
            }
        }

        AndFile file = null;
        if (currentPath.getCurrent().getType() == AndFile.TYPE_FILE) {
            file = AndFile.createDescriptor(new File(currentPath.getPath(), filename));
        } else {
            DocumentFile df = createDocumentFile(filename);
            if (df == null)
                return null;

            file = AndFile.createDescriptor(df);
        }

        if (writeFile(file, ctx, body))
            return file;

        return null;
    }

    private DocumentFile createDocumentFile(String filename) {
        String mime = DataAccessUtil.getFileNameMime(filename);
        if (mime.isEmpty())
            mime = "text/plain";
        DocumentFile df = ((DocumentFile)currentPath.getCurrent().getFile()).createFile(mime, filename);
        if (df == null) {
            StringBuilder newName = new StringBuilder(filename);
            int pidx = filename.lastIndexOf(".");
            if (pidx >= 0 && pidx < filename.length() - 1) {
                newName.delete(pidx + 1, newName.length());
                newName.append(DataAccessUtil.checkExt(filename.substring(pidx + 1), "txt"));
            } else if (pidx == filename.length() - 1) {
                newName.append("txt");
            } else
                newName.append(".txt");
            pidx = newName.lastIndexOf(".");

            AndFile[] files = currentPath.listFiles();
            boolean exists;
            int count = 0;
            do {
                exists = false;
                for (AndFile f : files) {
                    if (f.getName().equals(newName.toString())) {
                        exists = true;
                        if (count > 0)
                            newName.delete(pidx - 3, pidx);
                        newName.insert(pidx - 1,
                                "(" + Integer.toString(count) + ")");
                        if (count == 0)
                            pidx += 3;
                        break;
                    }
                }
                count++;
            } while (exists && count < 10);

            if (count == 10) {
                Log.e("TEdit.Browser", "Unable to save file " + currentPath.getPath() + "/" + filename
                        + ". The Android distribution renamed the file to an unknown existing filename.");
                ErrorMessage em = ErrorMessage.getInstance(ctx.getString(R.string.error),
                        ctx.getString(R.string.error_androidfilerename));
                em.show(ctx.getSupportFragmentManager(), "dialog");
                return null;
            }

            newName.delete(pidx, newName.length());
            df = ((DocumentFile)currentPath.getCurrent().getFile()).createFile(mime, newName.toString());
            if (df == null) {
                Log.e("TEdit.Browser", "Unable to save file " + currentPath.getPath() + "/" + filename
                        + ". The Android distribution renamed the file to an unknown existing filename.");
                ErrorMessage em = ErrorMessage.getInstance(ctx.getString(R.string.error),
                        ctx.getString(R.string.error_androidfilerename));
                em.show(ctx.getSupportFragmentManager(), "dialog");
                return null;
            }
        }

        if (!df.getName().equals(filename)) {
            Log.e("TEdit.Browser", "Android could not save the file "
                    + "under the requested name, " + filename
                    + ". The file was saved under the name: " + df.getName());
            ErrorMessage em = ErrorMessage.getInstance(ctx.getString(R.string.alert),
                    ctx.getString(R.string.alert_androidrenamedfile) + " " + df.getName());
            em.show(ctx.getSupportFragmentManager(), "dialog");
        }

        return df;
    }

    private static boolean writeFile(AndFile file, TEditActivity ctx, String body) {
        boolean err = false;
        try {
            DataAccessUtil.writeFile(file, ctx, body);
        } catch (IOException e) {
            err = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!ctx.checkWritePermission()) {
                    Log.e("TEdit.Browser", "Unable to save file " + file.getPath() + ". Permission denied: "
                            + e.getMessage());
                    ErrorMessage em = ErrorMessage.getInstance(ctx.getString(R.string.alert),
                            ctx.getString(R.string.error_nowritepermission));
                    em.show(ctx.getSupportFragmentManager(), "dialog");

                    return false;
                }
            }

            if (!file.getPath().startsWith(Environment.getExternalStorageDirectory().getPath())) {
                ErrorMessage em = ErrorMessage.getInstance(ctx.getString(R.string.alert),
                        ctx.getString(R.string.error_protectedpath));
                em.show(ctx.getSupportFragmentManager(), "dialog");

                return false;
            }

            Log.e("TEdit.Browser", "Unable to save file " + file.getPath() + ": "
                    + e.getMessage());
            ErrorMessage em = ErrorMessage.getInstance(ctx.getString(R.string.alert),
                    ctx.getString(R.string.error_writefile));
            em.show(ctx.getSupportFragmentManager(), "dialog");

            return false;
        }
        //Update the media library with the new file.
        if (!err) {
            if (file.getType() == AndFile.TYPE_FILE) {
                String mime = DataAccessUtil.getFileNameMime(file.getName());
                mime = (mime.isEmpty()) ? "text/plain" : mime;
                MediaScannerConnection.scanFile(ctx, new String[]{file.getPath()},
                        new String[]{mime}, null);
            }

            return true;
        }

        return false;
    }

    public void setVolume(AndFile volume) {
        if (currentPath.getRoot().getPathIdentifier().equals(volume.getPathIdentifier()))
            return;

        if (!volume.exists()) {
            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                    getString(R.string.missing_dir));
            em.show(ctx.getSupportFragmentManager(), "dialog");
            return;
        }

        currentPath = AndPath.fromAndFile(volume);
        populateBrowser();
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void launchVolumePicker() {
        VolumePicker vp = VolumePicker.newInstance(currentPath.getRoot().getPathIdentifier());
        vp.show(ctx.getSupportFragmentManager(), "VolumePicker");
    }

    private class DirFilter implements AndFileFilter {
        public boolean accept(AndFile file) {
            return file.isDirectory();
        }
    }

    private class TxtFilter implements AndFileFilter {
        public boolean accept(AndFile file) {
            if (file.isDirectory())
                return false;

            return !DataAccessUtil.hasExtension(file.getName()) || DataAccessUtil.mimeSupported(file.getMIME());
        }
    }

    public void applySettings() {
        populateBrowser();

        final TextView pathView = type == TYPE_OPEN ? (TextView)getView().findViewById(R.id.browsepath)
                : (TextView)getView().findViewById(R.id.savebrowsepath);
        pathView.setTypeface(FontUtil.getSystemTypeface());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            pathView.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            HorizontalScrollView.LayoutParams lp = (HorizontalScrollView.LayoutParams)pathView.getLayoutParams();
            if (Settings.getSystemTextDirection() == Settings.TEXTDIR_LTR) {
                pathView.setTextDirection(View.TEXT_DIRECTION_LTR);
                lp.gravity = Gravity.CENTER_VERTICAL|Gravity.LEFT;
            } else {
                pathView.setTextDirection(View.TEXT_DIRECTION_RTL);
                lp.gravity = Gravity.CENTER_VERTICAL|Gravity.RIGHT;
            }
            pathView.setLayoutParams(lp);
        }

        if (type == TYPE_SAVE) {
            TextView filename = getView().findViewById(R.id.filename);
            filename.setEnabled(true);
            filename.setTypeface(FontUtil.getEditorTypeface());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                filename.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
                filename.setTextDirection((Settings.getSystemTextDirection() == Settings.TEXTDIR_LTR) ?
                        View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
            }
        }
    }

    public static class OverwriteDialog extends TDialog {
        private AndPath path = null;
        private String error = "";
        private String name;
        private String body;
        private TEditActivity ctx;

        @Override
        public void onAttach(Context context) {
            super.onAttach(context);
            this.ctx = (TEditActivity)context;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            if (savedInstanceState == null) {
                Bundle bundle = getArguments();
                String filePath = bundle.getString("Overwrite.filePath", "");
                try {
                    path = AndPath.fromJson(ctx, filePath);
                } catch (FileNotFoundException e ) {
                    path = null;
                    Log.e("TEdit.Overwrite", "Unable to save file " + path.getPath() + "/" + name +
                            ": The path does not exist.");
                    error = getString(R.string.error_nofilepath);
                } catch (JSONException je) {
                    path = null;
                    error = getString(R.string.error_json);
                }

                name = bundle.getString("Overwrite.fileName", "");
                if (name == null)
                    error = getString(R.string.error_nofilename);
                body = bundle.getString("Overwrite.body", null);
                if (body == null)
                    error = getString(R.string.error_newtextbody);
            } else {
                String filePath = savedInstanceState.getString("Overwrite.filePath", "");
                error = savedInstanceState.getString("Overwrite.error", "");
                if (error.isEmpty()) {
                    try {
                        path = AndPath.fromJson(ctx, filePath);
                    } catch (FileNotFoundException e) {
                        path = null;
                        Log.e("TEdit.Overwrite", "Unable to save file " + name +
                                ": The path does not exist.");
                        error = getString(R.string.error_nofilepath);
                    } catch (JSONException je) {
                        path = null;
                        error = getString(R.string.error_json);
                    }

                    name = savedInstanceState.getString("Overwrite.fileName", "");
                    if (name == null)
                        error = getString(R.string.error_nofilename);
                    body = savedInstanceState.getString("Overwrite.body", null);
                    if (body == null)
                        error = getString(R.string.error_newtextbody);
                }
            }

            String title = error.isEmpty() ? getString(R.string.overwrite) : getString(R.string.error);
            String message = error.isEmpty() ? getString(R.string.overwrite_message).replace("%s", name)
                    : error;

            setTitle(title);
            setMessage(message);
            if (error.isEmpty()) {
                setPositiveButton(getActivity().getString(R.string.okay), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();

                        AndFile file;
                        if (path.getCurrent().getType() == AndFile.TYPE_FILE) {
                            file = AndFile.createDescriptor(new File(path.getPath(), name));
                        } else {
                            AndFile[] files = path.listFiles();
                            AndFile owFile = null;
                            for (AndFile f : files) {
                                if (f.isFile() && f.getName().equals(name)) {
                                    owFile = f;
                                    break;
                                }
                            }

                            if (owFile != null) {
                                file = owFile;
                            } else {
                                Log.e("TEdit.Overwrite", "Unable to save file " + path.getPath() + "/" + name +
                                        ": The file does not exist.");
                                ErrorMessage em = ErrorMessage.getInstance(getString(R.string.error),
                                        getString(R.string.error_nofilepath));
                                em.show(getActivity().getSupportFragmentManager(), "dialog");
                                return;
                            }
                        }

                        if (!writeFile(file, ctx, body))
                            return;

                        if (ctx.getFrag() instanceof Browser)
                            ctx.setSavePath(((Browser)ctx.getFrag()).getCurrentPath());

                        ctx.getDB().updateText(ctx.getLastTxt(), file.getPathIdentifier(), body);

                        Cursor cursor = ctx.getDB().fetchText(ctx.getLastTxt());
                        if (cursor.getColumnIndex(TEditDB.KEY_DATA) != -1) {
                            TxtSettings settings = new TxtSettings(cursor.getBlob(cursor.getColumnIndex(TEditDB.KEY_DATA)));
                            settings.saved = true;
                            ctx.getDB().updateTextState(ctx.getLastTxt(), settings);
                        }
                        cursor.close();

                        ctx.openDocument(ctx.getLastTxt());
                        Toast.makeText(ctx, getString(R.string.filesaved), Toast.LENGTH_SHORT).show();
                    }
                });
                setNegativeButton(getActivity().getString(R.string.cancel), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                    }
                });
            } else {
                setNeutralButton(R.string.okay, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                    }
                });
            }

            return super.onCreateDialog(savedInstanceState);
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putString("Overwrite.filePath", path != null ? path.toJson() : "");
            if (!error.isEmpty())
                outState.putString("Overwrite.error", error);
            outState.putString("Overwrite.fileName", name);
            outState.putString("Overwrite.body", body);
        }
    }

    public static class NewDirectory extends TDialog {
        private TEditActivity ctx;
        private AndPath path;
        private EditText et;
        private String error = "";

        public static NewDirectory newInstance(String createIn) {
            NewDirectory nd = new NewDirectory();
            Bundle bundle = new Bundle();
            bundle.putString("TEdit.newdirectory", createIn);
            nd.setArguments(bundle);
            return nd;
        }

        @Override
        public void onAttach(Context context) {
            super.onAttach(context);
            this.ctx = (TEditActivity)context;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String inDir;
            String name = "";
            if (savedInstanceState == null) {
                inDir = getArguments().getString("TEdit.newdirectory", "");
            } else {
                error = savedInstanceState.getString("TEdit.newDirectory.error", "");
                inDir = savedInstanceState.getString("TEdit.newdirectory", "");
                name = savedInstanceState.getString("TEdit.newDirectory.name", "");
            }

            if (error.isEmpty()) {
                try {
                    path = AndPath.fromJson(ctx, inDir);
                } catch (FileNotFoundException e) {
                    path = null;
                    error = getString(R.string.error_nofilepath);
                } catch (JSONException je) {
                    path = null;
                    error = getString(R.string.error_json);
                }
            } else
                path = null;

            if (path != null) {
                et = new EditText(new ContextThemeWrapper(ctx, R.style.Coffee_Cream));
                if (!name.isEmpty())
                    et.setText(name);
                et.setTypeface(FontUtil.getEditorTypeface());

                setTitle(R.string.newdirectory);
                setMessage(R.string.newdirmessage);
                setView(et);
                setNegativeButton(R.string.cancel, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (et.hasFocus()) {
                            InputMethodManager imm = (InputMethodManager)
                                    ctx.getSystemService(Activity.INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                        }
                        dismiss();
                    }
                });
                setPositiveButton(getString(R.string.okay), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (et.hasFocus()) {
                            InputMethodManager imm = (InputMethodManager)
                                    ctx.getSystemService(Activity.INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                        }

                        dismiss();
                        String dirName = et.getText().toString();
                        if (!isValidName(dirName)) {
                            ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                                    getString(R.string.error_invalidname));
                            em.show(ctx.getSupportFragmentManager(), "dialog");

                            return;
                        }

                        if (path.getCurrent().getType() == AndFile.TYPE_FILE) {
                            File dir = new File(path.getPath(), dirName);
                            if (dir.exists()) {
                                ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                                        getString(R.string.error_direxists));
                                em.show(ctx.getSupportFragmentManager(), "dialog");

                                return;
                            }

                            if (!dir.mkdirs()) {
                                ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                                        getString(R.string.error_nonewdir));
                                em.show(ctx.getSupportFragmentManager(), "dialog");

                                return;
                            }
                        } else {
                            DocumentFile newDir = ((DocumentFile)path.getCurrent().getFile())
                                    .createDirectory(dirName);
                            if (newDir == null) {
                                ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                                        getString(R.string.error_nonewdir));
                                em.show(ctx.getSupportFragmentManager(), "dialog");

                                return;
                            }
                        }

                        Toast.makeText(ctx, getString(R.string.dircreated), Toast.LENGTH_SHORT).show();
                        ((Browser) ctx.getFrag()).populateBrowser();
                    }
                });
            } else {
                setTitle(R.string.error);
                setMessage(error);
                setNeutralButton(R.string.okay, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                    }
                });
            }

            return super.onCreateDialog(savedInstanceState);
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);

            outState.putString("TEdit.newdirectory", path.toJson());
            if (!error.isEmpty())
                outState.putString("TEdit.newDirectory.error", error);
            if (et != null)
                outState.putString("TEdit.newDirectory.name", et.getText().toString());
        }
    }

    public static class DeleteDialog extends TDialog {
        private TEditActivity ctx;
        private AndFile file;

        public static DeleteDialog newInstance(String filePath) {
            DeleteDialog dd = new DeleteDialog();
            Bundle bundle = new Bundle();
            bundle.putString("TEdit.deleteDialog.filePath", filePath);
            dd.setArguments(bundle);
            return dd;
        }

        @Override
        public void onAttach(Context context) {
            super.onAttach(context);
            this.ctx = (TEditActivity)context;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String filePath;
            String error = "";
            if (savedInstanceState == null) {
                filePath = getArguments().getString("TEdit.deleteDialog.filePath", "");
            } else
                filePath = savedInstanceState.getString("TEdit.deleteDialog.filePath", "");

            if (filePath.isEmpty())
                error = getString(R.string.error_nofilepath);

            if (error.isEmpty()) {
                file = AndFile.createDescriptor(filePath, ctx);
            } else
                file = null;

            setTitle(R.string.delete);
            setMessage(getString(R.string.delete_msg) + " " + file.getName());
            setNegativeButton(R.string.cancel, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
            setPositiveButton(R.string.okay, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (!file.delete()) {
                        ErrorMessage em = ErrorMessage.getInstance(getString(R.string.alert),
                                getString(R.string.error_delete));
                        em.show(ctx.getSupportFragmentManager(), "dialog");
                    } else {
                        Browser browser = (Browser)ctx.getFrag();
                        Parcelable state = browser.getListView().onSaveInstanceState();
                        browser.populateBrowser();
                        browser.getListView().onRestoreInstanceState(state);
                        Toast.makeText(ctx, getString(R.string.filedeleted), Toast.LENGTH_SHORT).show();
                    }
                }
            });

            return super.onCreateDialog(savedInstanceState);
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);

            outState.putString("TEdit.deleteDialog.filePath", file.getPathIdentifier());
        }
    }
}
