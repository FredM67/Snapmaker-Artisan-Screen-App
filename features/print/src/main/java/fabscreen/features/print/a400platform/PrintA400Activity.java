package fabscreen.features.print.a400platform;

import android.app.AlertDialog;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.alibaba.android.arouter.facade.annotation.Route;

import fabscreen.features.print.R;
import fabscreen.platform.base.RoutePath;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.IRouter;
import fabscreen.platform.base.service.machine.controller.MachineOperationStatus;
import fabscreen.platform.base.service.machine.controller.NewPrintController;
import fabscreen.platform.base.view.BaseActivity;

@Route(path = RoutePath.PRINT_PRINT_A400)
public class PrintA400Activity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_print);
        IMachine.WorkType workType = mMachine.getMachineInfoSubjectHolder().getValue().workType;
        NewPrintController newPrintController = ServiceContainer.getInstance().getService(IMachine.class).getNewPrintController();
        if (isPrinting()
                || newPrintController.getRecoveryFlag()
                || newPrintController.getStartFromRemoteFlag()
                || newPrintController.hasAttachStartedRemotePrint()) {
            goToPrint();
        } else {
            // Go to print or prepare page due to the current work type.
            switch (workType) {
                case FDM:
                    // FDM work mode don't need to prepare anything, go to print directly.
                    goToPrint();
                    break;
                case CNC:
                    // CNC work mode requires set work origin before G-code start, go to set origin page first.
                    goToSetOrigin();
                    break;
                case LASER:
                    // Laser work mode also requires set work origin,
                    // but due to the laser focal and material height, we need to calculate working z position first.
                    gotoLaserPrepareStep();
                    break;
                case NONE:
                default:
                    // It shouldn't go to print page if current work type was not detected or undefined.
                    new AlertDialog.Builder(this)
                            .setTitle("WORK TYPE ERROR")
                            .setMessage("Not detected work type, now is " + workType)
                            .create()
                            .show();
                    break;
            }
        }
    }

    private boolean isPrinting() {
        NewPrintController controller = mMachine.getNewPrintController();
        return MachineOperationStatus.isPrinting(controller.getPrintState());
    }

    public void getSetZ() {
        ServiceContainer.getInstance().getService(IRouter.class)
                .routeWithClassPath(RoutePath.PRINT_LASER_SET_Z_SELECT)
                .start(this);
    }

    public void goToSetOrigin() {
        replaceFragment(R.id.print_master_container, SetOriginFragment.newInstance());
    }

    public void goToPrint() {
        // We won't need these fragments anymore after we go to print page.
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            getSupportFragmentManager().beginTransaction().remove(fragment).commit();
        }

        // Print Page(Fragment)
        addFragment(R.id.print_master_container, new PrintA400Fragment());
    }

    public void gotoPrintCompleteFragment() {
        PrintA400CompleteFragment fragment = new PrintA400CompleteFragment();
        addFragment(R.id.print_master_container, fragment);
    }

    public void gotoLaserPrepareStep() {
        replaceFragment(R.id.print_master_container, A400LaserSetZFragment.newInstance());
    }

    public void gotoA400AdjustmentContainerFragment() {
        PrintA400AdjustmentContainerFragment fragment = new PrintA400AdjustmentContainerFragment();
        addFragment(R.id.print_master_container, fragment, false);
    }

    @Override
    public void onFinishSuccess(String fileName, int printTime) {
        // NoToDo
    }
}
