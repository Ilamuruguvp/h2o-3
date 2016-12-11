package hex;

import hex.ensemble.StackedEnsemble;
import hex.genmodel.utils.DistributionFamily;
import hex.glm.GLMModel;
import water.DKV;
import water.H2O;
import water.Job;
import water.Key;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.Frame;
import water.nbhm.NonBlockingHashSet;
import water.util.Log;
import water.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.Arrays;

import static hex.Model.Parameters.FoldAssignmentScheme.Modulo;

/**
 * An ensemble of other models, created by <i>stacking</i> with the SuperLearner algorithm or a variation.
 */
public class StackedEnsembleModel extends Model<StackedEnsembleModel,StackedEnsembleModel.StackedEnsembleParameters,StackedEnsembleModel.StackedEnsembleOutput> {

  public ModelCategory modelCategory;
  public Frame commonTrainingFrame = null;
  public String responseColumn = null;
  private NonBlockingHashSet<String> names = null;  // keep columns as a set for easier comparison
  public int nfolds = -1;
  // TODO: add a separate holdout dataset for the ensemble
  // TODO: add a separate overall cross-validation for the ensemble, including _fold_column and FoldAssignmentScheme / _fold_assignment

  public StackedEnsembleModel(Key selfKey, StackedEnsembleParameters parms, StackedEnsembleOutput output) {
    super(selfKey, parms, output);
  }

  public static class StackedEnsembleParameters extends Model.Parameters {
    public String algoName() { return "StackedEnsemble"; }
    public String fullName() { return "Stacked Ensemble"; }
    public String javaName() { return StackedEnsembleModel.class.getName(); }
    @Override public long progressUnits() { return 1; }  // TODO

    public static enum SelectionStrategy { choose_all }

    // TODO: make _selection_strategy an object:
    /** How do we choose which models to stack? */
    public SelectionStrategy _selection_strategy;

    /** Which models can we choose from? */
    public Key<Model> _base_models[] = new Key[0];
  }

  public static class StackedEnsembleOutput extends Model.Output {
    public StackedEnsembleOutput() { super(); }
    public StackedEnsembleOutput(StackedEnsemble b) { super(b); }

    public StackedEnsembleOutput(Job job) { _job = job; }
    // The metalearner model (e.g., a GLM that has a coefficient for each of the base_learners).
    public Model _meta_model;
  }

  /**
   * @see Model#score0(double[], double[])
   */
  protected double[] score0(double data[/*ncols*/], double preds[/*nclasses+1*/]) {
    // TODO: don't score models that have 0 coefficients / aren't used by the metalearner

    // For each base learner, compute and save the predictions.
    // For binary classification this array is [_base_models.length][3].
    double[/*baseIdx*/][/*nclasses+1*/] basePreds = new double[this._parms._base_models.length][preds.length];

    // TODO: optimize these DKV lookups:
    int baseIdx = 0;
    for (Key<Model> baseKey : this._parms._base_models) {
      Model base = baseKey.get();  // TODO: cacheme!
      base.score0(data, basePreds[baseIdx]);
      baseIdx++;
    }

    // TODO: multiclass
    // TODO: regression
    // TODO: include_training_features
    double[] basePredsRotated = new double[this._parms._base_models.length];
    ModelCategory modelCategory = this._output.getModelCategory();
    for (baseIdx = 0; baseIdx < this._parms._base_models.length; baseIdx++) {
      if (modelCategory == ModelCategory.Binomial)
        basePredsRotated[baseIdx] = basePreds[baseIdx][2];
      else if (modelCategory == ModelCategory.Regression)
        basePredsRotated[baseIdx] = basePreds[baseIdx][0];
      else
        throw new H2OIllegalArgumentException("Don't know how to handle predictions frame for model category: " + modelCategory);
    }

    return _output._meta_model.score0(basePredsRotated, preds);
  }

  @Override public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    switch (_output.getModelCategory()) {
      case Binomial:
        return new ModelMetricsBinomial.MetricBuilderBinomial(domain);
      // case Multinomial: return new ModelMetricsMultinomial.MetricBuilderMultinomial(_output.nclasses(),domain);
      case Regression:
        return new ModelMetricsRegression.MetricBuilderRegression();
      default:
        throw H2O.unimpl();
    }
  }

  public ModelMetrics doScoreMetricsOneFrame(Frame frame) {
    // For GainsLift and Huber, we need the full predictions to compute the model metrics
    boolean needPreds = _output.nclasses() == 2 /* gains/lift table requires predictions */ || _parms._distribution== DistributionFamily.huber;

    if (needPreds) {
      Frame preds = null;
      preds = score(frame);
      preds.remove();
      return ModelMetrics.getFromDKV(this, frame);
    } else {
      // no need to allocate predictions
      ModelMetrics.MetricBuilder mb = scoreMetrics(frame);
      return mb.makeModelMetrics(this, frame, frame, null);
    }
  }

  public void doScoreMetrics() {

    this._output._training_metrics = doScoreMetricsOneFrame(this._parms.train());
    if (null != this._parms.valid()) {
      this._output._validation_metrics = doScoreMetricsOneFrame(this._parms.valid());
    }
/*
    adaptFr = new Frame(this._parms.train());
    this.adaptTestForTrain(adaptFr, true, !isSupervised());
    mb = this.scoreMetrics(adaptFr);
    this._output._training_metrics  = mb.makeModelMetrics(this, adaptFr, adaptFr, null);

    if (null != this._parms.valid()) {
      adaptFr = new Frame(this._parms.valid());
      this.adaptTestForTrain(adaptFr, true, !isSupervised());
      mb = this.scoreMetrics(adaptFr);
      this._output._validation_metrics  = mb.makeModelMetrics(this, adaptFr, adaptFr, null);
    }
    */
  }

  private DistributionFamily distributionFamily(Model aModel) {
    try {
      Field familyField = ReflectionUtils.findNamedField(aModel._parms, "_family");
      Field distributionField = (familyField != null ? null : ReflectionUtils.findNamedField(aModel, "_dist"));
      if (null != familyField) {
        // GLM only, for now
        GLMModel.GLMParameters.Family thisFamily = (GLMModel.GLMParameters.Family) familyField.get(aModel._parms);
        if (thisFamily == GLMModel.GLMParameters.Family.binomial) {
          return DistributionFamily.bernoulli;
        }

        try {
          return Enum.valueOf(DistributionFamily.class, thisFamily.toString());
        }
        catch (IllegalArgumentException e) {
          throw new H2OIllegalArgumentException("Don't know how to find the right DistributionFamily for Family: " + thisFamily);
        }
      } else if (null != distributionField) {
        Distribution distribution = ((Distribution)distributionField.get(aModel));
        if (null != distribution)
          return distribution.distribution;
        else
          return aModel._parms._distribution; // TODO: could be AUTO. . .
      } else {
        throw new H2OIllegalArgumentException("Don't know how to stack models that have neither a distribution hyperparameter nor a family hyperparameter.");
      }
    }
    catch (Exception e) {
      throw new H2OIllegalArgumentException(e.toString(), e.toString());
    }
  }

  public void checkAndInheritModelProperties() {
    if (null == _parms._base_models || 0 == _parms._base_models.length)
      throw new H2OIllegalArgumentException("When creating a StackedEnsemble you must specify one or more models; found 0.");

    if (null != _parms._ignored_columns)
      throw new H2OIllegalArgumentException("A StackedEnsemble takes its ignored_columns list from the base models.  Do not specify ignored_columns for the ensemble model.");

    Model aModel = null;
    boolean beenHere = false;

    for (Key<Model> k : _parms._base_models) {
      aModel = DKV.getGet(k);
      if (null == aModel) {
        Log.warn("Failed to find base model; skipping: " + k);
        continue;
      }

      if (beenHere) {
        // check that the base models are all consistent
        if (_output._isSupervised ^ aModel.isSupervised())
          throw new H2OIllegalArgumentException("Base models are inconsistent: there is a mix of supervised and unsupervised models: " + Arrays.toString(_parms._base_models));

        if (modelCategory != aModel._output.getModelCategory())
          throw new H2OIllegalArgumentException("Base models are inconsistent: there is a mix of different categories of models: " + Arrays.toString(_parms._base_models));

        Frame aTrainingFrame = aModel._parms.train();
        if (! commonTrainingFrame._key.equals(aTrainingFrame._key))
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different training frames.  Found: " + commonTrainingFrame._key + " and: " + aTrainingFrame._key + ".");

        NonBlockingHashSet<String> aNames = new NonBlockingHashSet<>();
        aNames.addAll(Arrays.asList(aModel._output._names));
        if (! aNames.equals(this.names))
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different column lists.  Found: " + this.names + " and: " + aNames + ".");

        if (! responseColumn.equals(aModel._parms._response_column))
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different response columns.  Found: " + responseColumn + " and: " + aModel._parms._response_column + ".");

        if (_output._domains.length != aModel._output._domains.length)
          throw new H2OIllegalArgumentException("Base models are inconsistent: there is a mix of different numbers of domains (categorical levels): " + Arrays.toString(_parms._base_models));

        if (nfolds != aModel._parms._nfolds)
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different values for nfolds.");

        // TODO: loosen this iff _parms._valid or if we add a separate holdout dataset for the ensemble
        if (aModel._parms._nfolds < 2)
          throw new H2OIllegalArgumentException("Base model does not use cross-validation: " + aModel._parms._nfolds);

        // TODO: loosen this iff it's consistent, like if we have a _fold_column
        if (aModel._parms._fold_assignment != Modulo)
          throw new H2OIllegalArgumentException("Base model does not use Modulo for cross-validation: " + aModel._parms._nfolds);

        if (! aModel._parms._keep_cross_validation_predictions)
          throw new H2OIllegalArgumentException("Base model does not keep cross-validation predictions: " + aModel._parms._nfolds);

        // In GLM, we get _family instead of _distribution.
        // Further, we have Family.binomial instead of DistributionFamily.bernoulli.
        DistributionFamily thisDistribution = distributionFamily(aModel);
        if (_parms._distribution != thisDistribution)
          throw new H2OIllegalArgumentException("Base models are inconsistent; they use different distributions: " + _parms._distribution + " and: " + thisDistribution);

        // TODO: If we're set to DistributionFamily.AUTO then GLM might auto-conform the response column
        // giving us inconsistencies.
      } else {
        _output._isSupervised = aModel.isSupervised();
        this.modelCategory = aModel._output.getModelCategory();
        this._dist = new Distribution(distributionFamily(aModel));
        _output._domains = Arrays.copyOf(aModel._output._domains, aModel._output._domains.length);
        commonTrainingFrame = aModel._parms.train();

        if (! commonTrainingFrame._key.equals(_parms._train))
          throw  new H2OIllegalArgumentException("StackedModel training_frame must match the training_frame of each base model.  Found: " + commonTrainingFrame._key + " and: " + _parms._train);

        // TODO: set _parms._train to aModel._parms.train()

        _output._names = aModel._output._names;
        this.names = new NonBlockingHashSet<>();
        this.names.addAll(Arrays.asList(aModel._output._names));

        responseColumn = aModel._parms._response_column;

        if (! responseColumn.equals(_parms._response_column))
          throw  new H2OIllegalArgumentException("StackedModel response_column must match the response_column of each base model.  Found: " + responseColumn + " and: " + _parms._response_column);

        nfolds = aModel._parms._nfolds;
        _parms._distribution = aModel._parms._distribution;
        beenHere = true;
      }

    } // for all base_models

    if (null == aModel)
      throw new H2OIllegalArgumentException("When creating a StackedEnsemble you must specify one or more models; " + _parms._base_models.length + " were specified but none of those were found: " + Arrays.toString(_parms._base_models));

  }

}
