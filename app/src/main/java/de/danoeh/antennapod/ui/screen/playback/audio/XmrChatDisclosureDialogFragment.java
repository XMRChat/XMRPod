package de.danoeh.antennapod.ui.screen.playback.audio;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.XmrchatDisclosureDialogBinding;

public class XmrChatDisclosureDialogFragment extends DialogFragment {
    public static final String TAG = "XmrChatDisclosureDialog";

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.xmrchat_disclosure_title)
                .setView(onCreateView(getLayoutInflater(), null, savedInstanceState))
                .create();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        XmrchatDisclosureDialogBinding viewBinding = XmrchatDisclosureDialogBinding.inflate(inflater);
        viewBinding.disclosureMessage.setText(R.string.xmrchat_disclosure_message);
        viewBinding.okButton.setOnClickListener(v -> dismiss());
        return viewBinding.getRoot();
    }
}
