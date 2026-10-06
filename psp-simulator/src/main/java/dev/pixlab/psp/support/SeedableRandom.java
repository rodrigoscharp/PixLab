package dev.pixlab.psp.support;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** Fonte de aleatoriedade que o motor de caos pode reiniciar com uma seed para tornar a execução reproduzível. */
public final class SeedableRandom implements RandomGenerator {

    private SplittableRandom delegate;

    public SeedableRandom(long seed) {
        this.delegate = new SplittableRandom(seed);
    }

    public synchronized void reseed(long seed) {
        delegate = new SplittableRandom(seed);
    }

    @Override
    public synchronized long nextLong() {
        return delegate.nextLong();
    }
}
