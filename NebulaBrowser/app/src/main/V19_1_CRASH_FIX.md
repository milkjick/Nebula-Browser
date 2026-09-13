# V19.1 crash fix

Fixed:
java.lang.IllegalStateException: The specified child already has a parent

The AI Agent UI now detaches a View from its existing ViewGroup parent before
re-adding it. This prevents repeated opening of the AI Agent panel from crashing
the main thread.

No new Gradle dependencies were added.
