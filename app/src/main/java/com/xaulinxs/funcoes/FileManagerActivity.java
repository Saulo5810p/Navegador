package com.xaulinxs.funcoes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.xaulinxs.aosp.browser.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * File manager próprio do app — substitui o seletor de arquivos padrão do
 * Android (DocumentsUI) tanto pra upload em sites (input type="file")
 * quanto pra navegação livre, navegando de verdade pelas pastas da
 * memória interna. Sem depender do DocumentsUI, que pode não existir ou
 * ter comportamento inconsistente em ROMs/GSIs minimalistas (mesmo
 * contexto de AOSP puro que motivou trocar o WebView provider).
 *
 * Portado de com.xaulinxs.webviewshell.FileManagerActivity (projeto
 * WebviewShell, abandonado), removendo o suporte a Typeface customizado
 * e a ação "Usar como fonte do app" — fora do escopo desta fase no
 * projeto Navegador.
 *
 * Dois modos de uso:
 * - MODE_PICK: toque num arquivo já seleciona e retorna (usado pelo upload).
 * - MODE_BROWSE: toque abre um menu de ações (Abrir / Enviar pro site se
 *   houver upload pendente / Excluir).
 */
public class FileManagerActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_PICK = "pick";
    public static final String MODE_BROWSE = "browse";
    public static final String EXTRA_FILTER_EXTENSIONS = "filter_extensions";
    public static final String EXTRA_HAS_PENDING_UPLOAD = "has_pending_upload";
    public static final String EXTRA_ACTION = "action";
    public static final String ACTION_UPLOAD = "upload";

    private static final int REQUEST_CODE_ALL_FILES_ACCESS = 900;

    private ListView listView;
    private TextView pathLabel;
    private File currentDir;
    private File rootDir;
    private String mode;
    private String[] filterExtensions;
    private boolean hasPendingUpload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_BROWSE;
        filterExtensions = getIntent().getStringArrayExtra(EXTRA_FILTER_EXTENSIONS);
        hasPendingUpload = getIntent().getBooleanExtra(EXTRA_HAS_PENDING_UPLOAD, false);

        listView = findViewById(R.id.file_list);
        pathLabel = findViewById(R.id.current_path);
        findViewById(R.id.btn_up).setOnClickListener(v -> navigateUp());

        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            FileEntry entry = (FileEntry) parent.getItemAtPosition(position);
            onEntryTapped(entry);
        });

        resolveRootAndOpen();
    }

    /** Decide a raiz de navegação de acordo com a permissão disponível. */
    private void resolveRootAndOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                openRoot(Environment.getExternalStorageDirectory());
            } else {
                showAllFilesAccessPrompt();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                openRoot(Environment.getExternalStorageDirectory());
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 901);
            }
        } else {
            openRoot(Environment.getExternalStorageDirectory());
        }
    }

    private void showAllFilesAccessPrompt() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_permission_title)
                .setMessage(R.string.file_manager_permission_msg)
                .setPositiveButton(R.string.file_manager_permission_grant, (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_CODE_ALL_FILES_ACCESS);
                })
                .setNegativeButton(R.string.file_manager_permission_deny,
                        (d, w) -> openRoot(getExternalFilesDir(null)))
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        openRoot(granted ? Environment.getExternalStorageDirectory() : getExternalFilesDir(null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_ALL_FILES_ACCESS) {
            boolean granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
            openRoot(granted ? Environment.getExternalStorageDirectory() : getExternalFilesDir(null));
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void openRoot(File root) {
        rootDir = root != null ? root : getExternalFilesDir(null);
        loadDir(rootDir);
    }

    private void loadDir(File dir) {
        currentDir = dir;
        pathLabel.setText(dir != null ? dir.getAbsolutePath() : "/");

        List<FileEntry> entries = new ArrayList<>();
        if (dir != null && !dir.equals(rootDir)) {
            entries.add(new FileEntry(FileEntry.Kind.UP, dir.getParentFile()));
        }

        File[] children = dir != null ? dir.listFiles() : null;
        if (children != null) {
            Arrays.sort(children, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File child : children) {
                if (child.isHidden()) continue;
                entries.add(new FileEntry(child.isDirectory() ? FileEntry.Kind.FOLDER : FileEntry.Kind.FILE, child));
            }
        }

        listView.setAdapter(new FileEntryAdapter(this, entries));
    }

    private void navigateUp() {
        if (currentDir != null && !currentDir.equals(rootDir) && currentDir.getParentFile() != null) {
            loadDir(currentDir.getParentFile());
        }
    }

    @Override
    public void onBackPressed() {
        if (currentDir != null && !currentDir.equals(rootDir)) {
            navigateUp();
        } else {
            super.onBackPressed();
        }
    }

    private void onEntryTapped(FileEntry entry) {
        if (entry.kind == FileEntry.Kind.UP || entry.kind == FileEntry.Kind.FOLDER) {
            loadDir(entry.file);
            return;
        }
        if (MODE_PICK.equals(mode)) {
            selectAndFinish(entry);
        } else {
            showFileActionSheet(entry);
        }
    }

    /** Modo "pick": aplica o filtro de extensão (se houver) e já retorna o arquivo escolhido. */
    private void selectAndFinish(FileEntry entry) {
        if (filterExtensions != null && filterExtensions.length > 0) {
            String ext = entry.extension();
            boolean matches = false;
            for (String allowed : filterExtensions) {
                if (allowed.equalsIgnoreCase(ext)) { matches = true; break; }
            }
            if (!matches) {
                Toast.makeText(this, R.string.file_manager_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Intent result = new Intent();
        result.setData(Uri.fromFile(entry.file));
        setResult(RESULT_OK, result);
        finish();
    }

    /** Modo "browse": pergunta o que fazer com o arquivo tocado. */
    private void showFileActionSheet(FileEntry entry) {
        List<String> actions = new ArrayList<>();
        actions.add(getString(R.string.file_manager_action_open));
        if (hasPendingUpload) actions.add(getString(R.string.file_manager_action_upload));
        actions.add(getString(R.string.file_manager_action_delete));

        new AlertDialog.Builder(this)
                .setTitle(entry.label())
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    String chosen = actions.get(which);
                    if (chosen.equals(getString(R.string.file_manager_action_open))) {
                        openFile(entry);
                    } else if (chosen.equals(getString(R.string.file_manager_action_upload))) {
                        Intent result = new Intent();
                        result.putExtra(EXTRA_ACTION, ACTION_UPLOAD);
                        result.setData(Uri.fromFile(entry.file));
                        setResult(RESULT_OK, result);
                        finish();
                    } else if (chosen.equals(getString(R.string.file_manager_action_delete))) {
                        confirmDelete(entry);
                    }
                })
                .show();
    }

    private void openFile(FileEntry entry) {
        try {
            Uri contentUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", entry.file);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension());
            if (mime == null) mime = "*/*";
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.file_manager_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(FileEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_action_delete)
                .setMessage(getString(R.string.file_manager_delete_confirm, entry.label()))
                .setPositiveButton(R.string.file_manager_action_delete, (d, w) -> {
                    if (entry.file.delete()) {
                        Toast.makeText(this, R.string.file_manager_deleted, Toast.LENGTH_SHORT).show();
                    }
                    loadDir(currentDir);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
