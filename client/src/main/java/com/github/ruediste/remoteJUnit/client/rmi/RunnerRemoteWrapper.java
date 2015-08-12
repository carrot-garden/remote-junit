package com.github.ruediste.remoteJUnit.client.rmi;

import java.io.IOException;
import java.rmi.RemoteException;

import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.manipulation.Sortable;
import org.junit.runner.manipulation.Sorter;
import org.junit.runner.notification.RunNotifier;

import com.github.ruediste.remoteJUnit.common.OutErrCombiningStream;
import com.github.ruediste.remoteJUnit.common.rmi.RunnerRemote;

public class RunnerRemoteWrapper extends Runner implements Sortable, Filterable {

    private RunnerRemote delegate;

    public RunnerRemoteWrapper(RunnerRemote delegate) {
        this.delegate = delegate;
    }

    @Override
    public void filter(Filter filter) throws NoTestsRemainException {
    }

    @Override
    public void sort(Sorter sorter) {

    }

    @Override
    public Description getDescription() {
        try {
            return delegate.getDescription();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run(RunNotifier notifier) {
        try {
            OutErrCombiningStream out = delegate.run(new RunNotifierRemoteImpl(
                    notifier));
            out.write(System.out, System.out);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}