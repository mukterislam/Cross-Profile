package com.mkr.crossprofile;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "DualFileManagerPrefs";
    private static final String KEY_SAF_URI = "saf_work_tree_uri";

    private TextView tvPersonalPath, tvWorkPath, tvPersonalStorageInfo, tvWorkStorageInfo;
    private ListView lvPersonal, lvWork;
    private ImageButton btnBackPersonal, btnBackWork, btnSortPersonal, btnSortWork;
    private ImageButton btnNewFolderPersonal, btnNewFolderWork, btnToggleHiddenPersonal, btnToggleHiddenWork;
    private Button btnPersonalToWork, btnWorkToPersonal;

    // Personal storage
    private File currentPersonalDir;
    private final List<File> personalFileList = new ArrayList<>();
    private final Set<File> selectedPersonalFiles = new HashSet<>();
    private PersonalAdapter personalAdapter;
    private int personalSortMode = 0;
    private boolean showHiddenPersonal = false;

    // Work storage (SAF)
    private DocumentFile currentWorkDir;
    private final List<DocumentFile> workFileList = new ArrayList<>();
    private final Set<DocumentFile> selectedWorkFiles = new HashSet<>();
    private final Stack<DocumentFile> workHistoryStack = new Stack<>();
    private WorkAdapter workAdapter;
    private int workSortMode = 0;
    private boolean showHiddenWork = false;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> safLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null) {
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

                        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                        prefs.edit().putString(KEY_SAF_URI, treeUri.toString()).apply();

                        initWorkStorage(treeUri);
                        Toast.makeText(this, "Work profile linked successfully!", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupAdapters();
        checkAndRequestStoragePermissions();
        checkAndInitWorkStorage();
        setupClickListeners();
    }

    private void initViews() {
        tvPersonalPath = findViewById(R.id.tvPersonalPath);
        tvWorkPath = findViewById(R.id.tvWorkPath);
        tvPersonalStorageInfo = findViewById(R.id.tvPersonalStorageInfo);
        tvWorkStorageInfo = findViewById(R.id.tvWorkStorageInfo);

        lvPersonal = findViewById(R.id.lvPersonal);
        lvWork = findViewById(R.id.lvWork);

        btnBackPersonal = findViewById(R.id.btnBackPersonal);
        btnBackWork = findViewById(R.id.btnBackWork);
        btnSortPersonal = findViewById(R.id.btnSortPersonal);
        btnSortWork = findViewById(R.id.btnSortWork);

        btnNewFolderPersonal = findViewById(R.id.btnNewFolderPersonal);
        btnNewFolderWork = findViewById(R.id.btnNewFolderWork);
        btnToggleHiddenPersonal = findViewById(R.id.btnToggleHiddenPersonal);
        btnToggleHiddenWork = findViewById(R.id.btnToggleHiddenWork);

        btnPersonalToWork = findViewById(R.id.btnPersonalToWork);
        btnWorkToPersonal = findViewById(R.id.btnWorkToPersonal);
    }

    private void setupAdapters() {
        personalAdapter = new PersonalAdapter();
        lvPersonal.setAdapter(personalAdapter);

        workAdapter = new WorkAdapter();
        lvWork.setAdapter(workAdapter);
    }

    private void checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                initPersonalStorage();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            } else {
                initPersonalStorage();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager() && currentPersonalDir == null) {
                initPersonalStorage();
            }
        }
    }

    private void checkAndInitWorkStorage() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String uriStr = prefs.getString(KEY_SAF_URI, null);
        if (uriStr != null) {
            initWorkStorage(Uri.parse(uriStr));
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Work Profile Setup")
                    .setMessage("Please select your Work profile directory to view and transfer files.")
                    .setCancelable(false)
                    .setPositiveButton("Setup Now", (dialog, which) -> launchSafPicker())
                    .setNegativeButton("Later", null)
                    .show();
        }
    }

    private void launchSafPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        safLauncher.launch(intent);
    }

    private void initPersonalStorage() {
        currentPersonalDir = Environment.getExternalStorageDirectory();
        loadPersonalDirectory(currentPersonalDir);
        updateStorageSpaceDisplay();
    }

    private void loadPersonalDirectory(File directory) {
        if (directory == null || !directory.exists()) return;
        currentPersonalDir = directory;
        tvPersonalPath.setText(directory.getAbsolutePath());
        selectedPersonalFiles.clear();

        File[] files = directory.listFiles();
        personalFileList.clear();

        if (files != null) {
            for (File file : files) {
                if (!showHiddenPersonal && file.getName().startsWith(".")) {
                    continue;
                }
                personalFileList.add(file);
            }
            sortPersonalFiles();
        }
        personalAdapter.notifyDataSetChanged();
    }

    private void sortPersonalFiles() {
        Collections.sort(personalFileList, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            switch (personalSortMode) {
                case 1: return Long.compare(f2.lastModified(), f1.lastModified());
                case 2: return Long.compare(f2.length(), f1.length());
                default: return f1.getName().compareToIgnoreCase(f2.getName());
            }
        });
    }

    private void initWorkStorage(Uri treeUri) {
        currentWorkDir = DocumentFile.fromTreeUri(this, treeUri);
        workHistoryStack.clear();
        loadWorkDirectory(currentWorkDir);
        updateStorageSpaceDisplay();
    }

    private void loadWorkDirectory(DocumentFile dir) {
        if (dir == null || !dir.exists()) return;
        currentWorkDir = dir;
        tvWorkPath.setText(dir.getName() != null ? dir.getName() : "Work Storage");
        selectedWorkFiles.clear();

        workFileList.clear();
        DocumentFile[] files = dir.listFiles();

        if (files != null) {
            for (DocumentFile file : files) {
                String name = file.getName();
                if (name != null) {
                    if (!showHiddenWork && name.startsWith(".")) {
                        continue;
                    }
                    workFileList.add(file);
                }
            }
            sortWorkFiles();
        }
        workAdapter.notifyDataSetChanged();
    }

    private void sortWorkFiles() {
        Collections.sort(workFileList, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            String n1 = f1.getName() != null ? f1.getName() : "";
            String n2 = f2.getName() != null ? f2.getName() : "";
            switch (workSortMode) {
                case 1: return Long.compare(f2.lastModified(), f1.lastModified());
                case 2: return Long.compare(f2.length(), f1.length());
                default: return n1.compareToIgnoreCase(n2);
            }
        });
    }

    private void updateStorageSpaceDisplay() {
        try {
            File path = Environment.getExternalStorageDirectory();
            StatFs stat = new StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalSpace = totalBlocks * blockSize;
            long freeSpace = availableBlocks * blockSize;

            String storageInfo = "Free: " + formatFileSize(freeSpace) + " / Total: " + formatFileSize(totalSpace);
            tvPersonalStorageInfo.setText(storageInfo);
            tvWorkStorageInfo.setText(storageInfo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupClickListeners() {
        lvPersonal.setOnItemClickListener((parent, view, position, id) -> {
            File clicked = personalFileList.get(position);
            if (clicked.isDirectory()) {
                loadPersonalDirectory(clicked);
            } else {
                if (selectedPersonalFiles.contains(clicked)) {
                    selectedPersonalFiles.remove(clicked);
                } else {
                    selectedPersonalFiles.add(clicked);
                }
                personalAdapter.notifyDataSetChanged();
            }
        });

        // Personal প্যানেলের ফাইল লং-প্রেস
        lvPersonal.setOnItemLongClickListener((parent, view, position, id) -> {
            File clicked = personalFileList.get(position);
            showPersonalContextMenu(clicked);
            return true;
        });

        lvWork.setOnItemClickListener((parent, view, position, id) -> {
            DocumentFile clicked = workFileList.get(position);
            if (clicked.isDirectory()) {
                workHistoryStack.push(currentWorkDir);
                loadWorkDirectory(clicked);
            } else {
                if (selectedWorkFiles.contains(clicked)) {
                    selectedWorkFiles.remove(clicked);
                } else {
                    selectedWorkFiles.add(clicked);
                }
                workAdapter.notifyDataSetChanged();
            }
        });

        // Work প্যানেলের ফাইল লং-প্রেস
        lvWork.setOnItemLongClickListener((parent, view, position, id) -> {
            DocumentFile clicked = workFileList.get(position);
            showWorkContextMenu(clicked);
            return true;
        });

        btnBackPersonal.setOnClickListener(v -> navigateBackPersonal());
        btnBackWork.setOnClickListener(v -> navigateBackWork());

        btnSortPersonal.setOnClickListener(v -> showSortDialog(true));
        btnSortWork.setOnClickListener(v -> showSortDialog(false));

        btnNewFolderPersonal.setOnClickListener(v -> createNewFolderPersonal());
        btnNewFolderWork.setOnClickListener(v -> createNewFolderWork());

        btnToggleHiddenPersonal.setOnClickListener(v -> {
            showHiddenPersonal = !showHiddenPersonal;
            btnToggleHiddenPersonal.setColorFilter(showHiddenPersonal ? Color.parseColor("#66BB6A") : Color.parseColor("#888888"));
            Toast.makeText(this, showHiddenPersonal ? "Hidden files visible" : "Hidden files hidden", Toast.LENGTH_SHORT).show();
            loadPersonalDirectory(currentPersonalDir);
        });

        btnToggleHiddenWork.setOnClickListener(v -> {
            showHiddenWork = !showHiddenWork;
            btnToggleHiddenWork.setColorFilter(showHiddenWork ? Color.parseColor("#42A5F5") : Color.parseColor("#888888"));
            Toast.makeText(this, showHiddenWork ? "Hidden files visible" : "Hidden files hidden", Toast.LENGTH_SHORT).show();
            loadWorkDirectory(currentWorkDir);
        });

        btnPersonalToWork.setOnClickListener(v -> {
            if (selectedPersonalFiles.isEmpty()) {
                Toast.makeText(this, "Select files from Personal panel", Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentWorkDir == null || !currentWorkDir.canWrite()) {
                showWorkSetupPrompt();
                return;
            }

            String[] options = {"Copy", "Move"};
            new AlertDialog.Builder(this)
                    .setTitle("Transfer: Personal ➔ Work")
                    .setItems(options, (dialog, which) -> {
                        boolean isMove = (which == 1);
                        processBatchPersonalToWork(isMove);
                    }).show();
        });

        btnWorkToPersonal.setOnClickListener(v -> {
            if (selectedWorkFiles.isEmpty()) {
                Toast.makeText(this, "Select files from Work panel", Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentPersonalDir == null || !currentPersonalDir.canWrite()) {
                Toast.makeText(this, "No write permission in Personal folder", Toast.LENGTH_SHORT).show();
                return;
            }

            String[] options = {"Copy", "Move"};
            new AlertDialog.Builder(this)
                    .setTitle("Transfer: Work ➔ Personal")
                    .setItems(options, (dialog, which) -> {
                        boolean isMove = (which == 1);
                        processBatchWorkToPersonal(isMove);
                    }).show();
        });
    }

    // --- Personal Context Menu (Share সহ) ---
    private void showPersonalContextMenu(File file) {
        String[] options;
        if (file.isDirectory()) {
            options = new String[]{"Rename", "Delete"};
        } else {
            options = new String[]{"Share", "Rename", "Delete"};
        }

        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    String selectedOption = options[which];
                    if (selectedOption.equals("Share")) {
                        sharePersonalFile(file);
                    } else if (selectedOption.equals("Rename")) {
                        showRenameDialog(file.getName(), newName -> {
                            File dest = new File(file.getParent(), newName);
                            if (file.renameTo(dest)) {
                                Toast.makeText(this, "Renamed successfully", Toast.LENGTH_SHORT).show();
                                loadPersonalDirectory(currentPersonalDir);
                            }
                        });
                    } else if (selectedOption.equals("Delete")) {
                        confirmDelete(() -> {
                            if (deleteRecursive(file)) {
                                Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show();
                                loadPersonalDirectory(currentPersonalDir);
                                updateStorageSpaceDisplay();
                            }
                        });
                    }
                }).show();
    }

    // --- Work Context Menu (Share সহ) ---
    private void showWorkContextMenu(DocumentFile file) {
        String[] options;
        if (file.isDirectory()) {
            options = new String[]{"Rename", "Delete"};
        } else {
            options = new String[]{"Share", "Rename", "Delete"};
        }

        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    String selectedOption = options[which];
                    if (selectedOption.equals("Share")) {
                        shareWorkFile(file);
                    } else if (selectedOption.equals("Rename")) {
                        showRenameDialog(file.getName(), newName -> {
                            if (file.renameTo(newName)) {
                                Toast.makeText(this, "Renamed successfully", Toast.LENGTH_SHORT).show();
                                loadWorkDirectory(currentWorkDir);
                            }
                        });
                    } else if (selectedOption.equals("Delete")) {
                        confirmDelete(() -> {
                            if (file.delete()) {
                                Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show();
                                loadWorkDirectory(currentWorkDir);
                                updateStorageSpaceDisplay();
                            }
                        });
                    }
                }).show();
    }

    // --- ফাইল শেয়ার করার লজিক (Personal Storage) ---
    private void sharePersonalFile(File file) {
        try {
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            String mimeType = getMimeType(file.getName());

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType != null ? mimeType : "*/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share file via"));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot share file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // --- ফাইল শেয়ার করার লজিক (Work Storage SAF) ---
    private void shareWorkFile(DocumentFile file) {
        try {
            String mimeType = file.getType();
            if (mimeType == null) {
                mimeType = getMimeType(file.getName());
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType != null ? mimeType : "*/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, file.getUri());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share file via"));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot share file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String fileName) {
        if (fileName == null) return "*/*";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0) {
            String ext = fileName.substring(lastDot + 1).toLowerCase();
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        return "*/*";
    }

    private boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDirectory.delete();
    }

    private void confirmDelete(Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Confirmation")
                .setMessage("Are you sure you want to delete this?")
                .setPositiveButton("Delete", (d, w) -> onConfirm.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createNewFolderPersonal() {
        if (currentPersonalDir == null || !currentPersonalDir.canWrite()) {
            Toast.makeText(this, "Cannot write to this folder", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Folder (Personal)");
        final EditText input = new EditText(this);
        input.setHint("Folder name");
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();
            if (!folderName.isEmpty()) {
                File newFolder = new File(currentPersonalDir, folderName);
                if (!newFolder.exists() && newFolder.mkdirs()) {
                    Toast.makeText(this, "Folder created successfully!", Toast.LENGTH_SHORT).show();
                    loadPersonalDirectory(currentPersonalDir);
                } else {
                    Toast.makeText(this, "Failed to create folder!", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void createNewFolderWork() {
        if (currentWorkDir == null || !currentWorkDir.canWrite()) {
            Toast.makeText(this, "Cannot write to Work folder", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Folder (Work)");
        final EditText input = new EditText(this);
        input.setHint("Folder name");
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();
            if (!folderName.isEmpty()) {
                DocumentFile newFolder = currentWorkDir.createDirectory(folderName);
                if (newFolder != null) {
                    Toast.makeText(this, "Folder created successfully!", Toast.LENGTH_SHORT).show();
                    loadWorkDirectory(currentWorkDir);
                } else {
                    Toast.makeText(this, "Failed to create folder!", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showWorkSetupPrompt() {
        new AlertDialog.Builder(this)
                .setTitle("Work Profile Not Linked")
                .setMessage("Please link your Work folder before transferring files.")
                .setPositiveButton("Link Now", (dialog, which) -> launchSafPicker())
                .setNegativeButton("Cancel", null)
                .show();
    }

    interface RenameCallback {
        void onRename(String newName);
    }

    private void showRenameDialog(String oldName, RenameCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename");
        final EditText input = new EditText(this);
        input.setText(oldName);
        builder.setView(input);
        builder.setPositiveButton("Save", (dialog, which) -> callback.onRename(input.getText().toString().trim()));
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showSortDialog(boolean isPersonal) {
        String[] sortOptions = {"Name (A-Z)", "Date (Newest first)", "Size (Largest first)"};
        new AlertDialog.Builder(this)
                .setTitle("Sort By")
                .setSingleChoiceItems(sortOptions, isPersonal ? personalSortMode : workSortMode, (dialog, which) -> {
                    if (isPersonal) {
                        personalSortMode = which;
                        sortPersonalFiles();
                        personalAdapter.notifyDataSetChanged();
                    } else {
                        workSortMode = which;
                        sortWorkFiles();
                        workAdapter.notifyDataSetChanged();
                    }
                    dialog.dismiss();
                }).show();
    }

    private void processBatchPersonalToWork(boolean isMove) {
        List<File> filesToProcess = new ArrayList<>(selectedPersonalFiles);
        ProgressDialog progressDialog = createProgressDialog(isMove ? "Moving..." : "Copying...");
        progressDialog.show();

        AtomicBoolean isCancelled = new AtomicBoolean(false);
        progressDialog.setOnCancelListener(dialog -> isCancelled.set(true));

        executorService.execute(() -> {
            int total = filesToProcess.size();
            for (int i = 0; i < total; i++) {
                if (isCancelled.get()) break;
                File src = filesToProcess.get(i);
                int progress = (int) (((i + 1) / (float) total) * 100);

                try {
                    DocumentFile destFile = currentWorkDir.createFile("application/octet-stream", src.getName());
                    if (destFile != null) {
                        try (InputStream in = new FileInputStream(src);
                             OutputStream out = getContentResolver().openOutputStream(destFile.getUri())) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = in.read(buffer)) != -1) {
                                if (isCancelled.get()) break;
                                out.write(buffer, 0, len);
                            }
                            out.flush();
                        }
                        if (isMove && !isCancelled.get()) {
                            src.delete();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                int finalI = i + 1;
                runOnUiThread(() -> {
                    progressDialog.setProgress(progress);
                    progressDialog.setMessage((isMove ? "Moving: " : "Copying: ") + finalI + "/" + total);
                });
            }

            runOnUiThread(() -> {
                progressDialog.dismiss();
                selectedPersonalFiles.clear();
                loadPersonalDirectory(currentPersonalDir);
                loadWorkDirectory(currentWorkDir);
                updateStorageSpaceDisplay();
                Toast.makeText(MainActivity.this, "Operation completed", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void processBatchWorkToPersonal(boolean isMove) {
        List<DocumentFile> filesToProcess = new ArrayList<>(selectedWorkFiles);
        ProgressDialog progressDialog = createProgressDialog(isMove ? "Moving..." : "Copying...");
        progressDialog.show();

        AtomicBoolean isCancelled = new AtomicBoolean(false);
        progressDialog.setOnCancelListener(dialog -> isCancelled.set(true));

        executorService.execute(() -> {
            int total = filesToProcess.size();
            for (int i = 0; i < total; i++) {
                if (isCancelled.get()) break;
                DocumentFile src = filesToProcess.get(i);
                int progress = (int) (((i + 1) / (float) total) * 100);

                try {
                    File dest = new File(currentPersonalDir, src.getName());
                    try (InputStream in = getContentResolver().openInputStream(src.getUri());
                         OutputStream out = new FileOutputStream(dest)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            if (isCancelled.get()) break;
                            out.write(buffer, 0, len);
                        }
                        out.flush();
                    }
                    if (isMove && !isCancelled.get()) {
                        src.delete();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                int finalI = i + 1;
                runOnUiThread(() -> {
                    progressDialog.setProgress(progress);
                    progressDialog.setMessage((isMove ? "Moving: " : "Copying: ") + finalI + "/" + total);
                });
            }

            runOnUiThread(() -> {
                progressDialog.dismiss();
                selectedWorkFiles.clear();
                loadWorkDirectory(currentWorkDir);
                loadPersonalDirectory(currentPersonalDir);
                updateStorageSpaceDisplay();
                Toast.makeText(MainActivity.this, "Operation completed", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private ProgressDialog createProgressDialog(String title) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(title);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(true);
        return pd;
    }

    private boolean navigateBackWork() {
        if (!workHistoryStack.isEmpty()) {
            loadWorkDirectory(workHistoryStack.pop());
            return true;
        }
        return false;
    }

    private boolean navigateBackPersonal() {
        if (currentPersonalDir != null && !currentPersonalDir.equals(Environment.getExternalStorageDirectory())) {
            File parent = currentPersonalDir.getParentFile();
            if (parent != null && parent.canRead()) {
                loadPersonalDirectory(parent);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (navigateBackWork()) return;
        if (navigateBackPersonal()) return;

        new AlertDialog.Builder(this)
                .setTitle("Exit App")
                .setMessage("Are you sure you want to exit?")
                .setPositiveButton("Yes", (dialog, which) -> finish())
                .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                .setCancelable(true)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }

    private static String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private static String formatDate(long time) {
        SimpleDateFormat sdf = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(time));
    }

    private void setFileIcon(ImageView ivIcon, String fileName, boolean isDirectory, Object sourceObject) {
        Glide.with(MainActivity.this).clear(ivIcon);

        if (isDirectory) {
            ivIcon.setImageResource(R.drawable.ic_folder);
            return;
        }

        String name = fileName.toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".gif")) {
            Glide.with(MainActivity.this)
                    .load(sourceObject)
                    .centerCrop()
                    .placeholder(R.drawable.ic_file_general)
                    .into(ivIcon);
        } else if (name.endsWith(".apk")) {
            if (sourceObject instanceof File) {
                try {
                    File file = (File) sourceObject;
                    PackageManager pm = getPackageManager();
                    PackageInfo pi = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
                    if (pi != null) {
                        pi.applicationInfo.sourceDir = file.getAbsolutePath();
                        pi.applicationInfo.publicSourceDir = file.getAbsolutePath();
                        Drawable icon = pi.applicationInfo.loadIcon(pm);
                        ivIcon.setImageDrawable(icon);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            ivIcon.setImageResource(R.drawable.ic_file_code);
        } else if (name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xml") ||
                name.endsWith(".md") || name.endsWith(".mht") || name.endsWith(".txt") ||
                name.endsWith(".json") || name.endsWith(".java") || name.endsWith(".log")) {
            ivIcon.setImageResource(R.drawable.ic_file_code);
        } else if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".3gp") || name.endsWith(".mov")) {
            ivIcon.setImageResource(R.drawable.ic_file_video);
        } else if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".ogg") || name.endsWith(".flac")) {
            ivIcon.setImageResource(R.drawable.ic_file_music);
        } else {
            ivIcon.setImageResource(R.drawable.ic_file_general);
        }
    }

    // --- Personal Adapter ---
    private class PersonalAdapter extends BaseAdapter {
        @Override
        public int getCount() { return personalFileList.size(); }
        @Override
        public Object getItem(int position) { return personalFileList.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_file, parent, false);
            }

            File file = personalFileList.get(position);
            ImageView ivIcon = convertView.findViewById(R.id.ivIcon);
            TextView tvFileName = convertView.findViewById(R.id.tvFileName);
            TextView tvFileDetails = convertView.findViewById(R.id.tvFileDetails);
            LinearLayout itemContainer = convertView.findViewById(R.id.itemContainer);

            tvFileName.setText(file.getName());

            if (file.isDirectory()) {
                tvFileDetails.setText(formatDate(file.lastModified()));
            } else {
                tvFileDetails.setText(formatDate(file.lastModified()) + "   " + formatFileSize(file.length()));
            }

            setFileIcon(ivIcon, file.getName(), file.isDirectory(), file);

            if (selectedPersonalFiles.contains(file)) {
                itemContainer.setBackgroundColor(Color.parseColor("#2E4C38"));
            } else {
                itemContainer.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }
    }

    // --- Work Adapter ---
    private class WorkAdapter extends BaseAdapter {
        @Override
        public int getCount() { return workFileList.size(); }
        @Override
        public Object getItem(int position) { return workFileList.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_file, parent, false);
            }

            DocumentFile file = workFileList.get(position);
            ImageView ivIcon = convertView.findViewById(R.id.ivIcon);
            TextView tvFileName = convertView.findViewById(R.id.tvFileName);
            TextView tvFileDetails = convertView.findViewById(R.id.tvFileDetails);
            LinearLayout itemContainer = convertView.findViewById(R.id.itemContainer);

            String name = file.getName() != null ? file.getName() : "Unknown";
            tvFileName.setText(name);

            if (file.isDirectory()) {
                tvFileDetails.setText(formatDate(file.lastModified()));
            } else {
                tvFileDetails.setText(formatDate(file.lastModified()) + "   " + formatFileSize(file.length()));
            }

            setFileIcon(ivIcon, name, file.isDirectory(), file.getUri());

            if (selectedWorkFiles.contains(file)) {
                itemContainer.setBackgroundColor(Color.parseColor("#1B3A57"));
            } else {
                itemContainer.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }
    }
}
